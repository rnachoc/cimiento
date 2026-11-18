import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Proposal } from '../api';
import { Inbox } from './inbox';

function proposal(id: number, type: string, status: Proposal['status'], extra: Partial<Proposal> = {}): Proposal {
  return {
    id, tenderId: 1, tenderCode: 'E-001', type, status, route: 'HUMAN', payload: { lines: [{}, {}] },
    executedPayload: null, citations: [], signals: [], ruleResults: [], routingReasons: ['TYPE_REQUIRES_REVIEW'],
    createdBy: 'AGENT', createdAt: '2026-11-18T10:00:00Z', decidedAt: null, ...extra,
  };
}

describe('Inbox', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Inbox],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('groups pending proposals in rails and counts statuses', async () => {
    const fixture = TestBed.createComponent(Inbox);
    http.expectOne('/api/proposals').flush([
      proposal(1, 'BUDGET', 'PENDING_REVIEW'),
      proposal(2, 'BUDGET', 'PENDING_REVIEW', { signals: ['INJECTION_DETECTED'] }),
      proposal(3, 'SCHEDULE', 'PENDING_REVIEW'),
      proposal(4, 'RISK_SIGNAL', 'EXECUTED'),
      proposal(5, 'SCHEDULE', 'RULES_FAILED'),
    ]);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="pending-count"]')?.textContent?.trim()).toBe('3');
    const rails = [...el.querySelectorAll('.rail h2')].map((h) => h.textContent?.trim());
    expect(rails).toEqual(['Presupuesto', 'Planning']);
    expect(el.querySelector('[data-testid="card-2"]')?.classList.contains('danger')).toBe(true);
    expect(el.querySelector('[data-testid="card-4"]')).toBeNull();
    expect(el.querySelector('[data-testid="card-1"]')?.getAttribute('aria-label')).toContain('Presupuesto de 2 partidas');
  });

  it('shows an error when the API fails', async () => {
    const fixture = TestBed.createComponent(Inbox);
    http.expectOne('/api/proposals').flush('no', { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent).toContain('HTTP 503');
  });
});
