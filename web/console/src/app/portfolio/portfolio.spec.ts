import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { PortfolioItem } from '../api';
import { daysLeft, Portfolio } from './portfolio';

function isoIn(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function item(id: number, code: string, extra: Partial<PortfolioItem> = {}): PortfolioItem {
  return {
    tenderId: id, code, title: `Licitación ${code}`, projectCode: 'LICITACIONES', stage: 'TO_REVIEW',
    stageChangedAt: null, bidDeadline: null, deadlineApproved: false, recommendation: null, pendingReviews: 0,
    proposals: 3, bidAnalysisApproved: false, next: ['UNDER_REVIEW', 'DISCARDED'], ...extra,
  };
}

describe('daysLeft', () => {
  it('counts whole calendar days from today', () => {
    const today = new Date(2027, 0, 4, 18, 30);
    expect(daysLeft('2027-01-04', today)).toBe(0);
    expect(daysLeft('2027-01-11', today)).toBe(7);
    expect(daysLeft('2027-01-01', today)).toBe(-3);
    expect(daysLeft('2027-04-01', today)).toBe(87); // across the daylight saving change
  });
});

describe('Portfolio', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Portfolio],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(rows: PortfolioItem[]) {
    const fixture = TestBed.createComponent(Portfolio);
    http.expectOne('/api/portfolio').flush(rows);
    await fixture.whenStable();
    return fixture;
  }

  it('places tenders in their stage columns and counts what is due', async () => {
    const fixture = await render([
      item(1, 'E-001', { bidDeadline: isoIn(3), recommendation: 'BID', pendingReviews: 4 }),
      item(2, 'E-002', { stage: 'DECISION_PENDING', bidDeadline: isoIn(-1), next: ['IN_PREPARATION', 'DISCARDED'] }),
      item(3, 'E-003', { stage: 'AWARDED', bidDeadline: isoIn(-30), next: [] }),
      item(4, 'E-004', { bidDeadline: isoIn(40) }),
    ]);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="open-count"]')?.textContent?.trim()).toBe('3');
    expect(el.querySelector('[data-testid="due-soon"]')?.textContent?.trim()).toBe('2');
    expect(el.querySelector('[data-testid="col-TO_REVIEW"]')?.querySelectorAll('article').length).toBe(2);
    expect(el.querySelector('[data-testid="col-AWARDED"] [data-testid="tender-E-003"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="tender-E-002"]')?.classList.contains('late')).toBe(true);
    expect(el.querySelector('[data-testid="tender-E-001"]')?.textContent).toContain('en 3 días');
    expect(el.querySelector('[data-testid="tender-E-001"]')?.textContent).toContain('Ofertar');
    expect(el.querySelector('[data-testid="tender-E-001"]')?.textContent).toContain('4 por revisar');
    // a final stage offers no moves
    expect(el.querySelector('[data-testid="tender-E-003"] button')).toBeNull();
  });

  it('moves a tender without a note when the stage does not need one', async () => {
    const fixture = await render([item(1, 'E-001')]);
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('[data-testid="move-E-001-UNDER_REVIEW"]') as HTMLButtonElement).click();
    const req = http.expectOne('/api/tenders/1/stage');
    expect(req.request.body).toEqual({ stage: 'UNDER_REVIEW' });
    req.flush(item(1, 'E-001', { stage: 'UNDER_REVIEW', next: ['DECISION_PENDING', 'DISCARDED'] }));
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="col-UNDER_REVIEW"] [data-testid="tender-E-001"]')).not.toBeNull();
  });

  it('asks for a reason before discarding and shows the core rules that block a move', async () => {
    const fixture = await render([item(1, 'E-001')]);
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('[data-testid="move-E-001-DISCARDED"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    // no request until a reason is written
    (el.querySelector('form button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Escribe el motivo');
    http.expectNone('/api/tenders/1/stage');

    const area = el.querySelector('textarea') as HTMLTextAreaElement;
    area.value = 'Fuera de nuestra zona';
    area.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    (el.querySelector('form button[type="submit"]') as HTMLButtonElement).click();
    const req = http.expectOne('/api/tenders/1/stage');
    expect(req.request.body).toEqual({ stage: 'DISCARDED', note: 'Fuera de nuestra zona' });
    req.flush({ error: 'unprocessable_entity', message: 'stage change not allowed', details: ['cannot move from TO_REVIEW to DISCARDED'] },
      { status: 422, statusText: 'Unprocessable' });
    await fixture.whenStable();
    expect(el.querySelector('.problems')?.textContent).toContain('cannot move from TO_REVIEW to DISCARDED');
  });
});
