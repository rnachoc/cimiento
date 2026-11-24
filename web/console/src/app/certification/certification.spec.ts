import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CertLine, CertPreview } from '../api';
import { Session } from '../session';
import { Certifications, groupByActivity, issueBlock } from './certification';

const line = (code: string, activity: string, amount: number, pct: number): CertLine => ({
  code, activity, description: code, amount, pct, toDate: amount * pct / 100, previous: 0, period: amount * pct / 100,
});

const PREVIEW: CertPreview = {
  tenderId: 3, tenderCode: 'E-003', budgetApproved: true, progressApproved: true,
  result: {
    month: '2027-05', expectedMonth: '2027-05', cutWeek: 3, cutDate: '2027-05-28',
    lines: [line('MT01', 'A01', 1000, 60), line('MT02', 'A01', 500, 60), line('CI01', 'A02', 2000, 10)],
    budgetTotal: 3500, toDate: 1100, previous: 0, period: 1100, retention: 55, net: 1045, vat: 219.45,
    payable: 1264.45, errors: [],
  },
};

describe('groupByActivity', () => {
  it('adds the lines of each activity and keeps its percent', () => {
    const g = groupByActivity(PREVIEW.result.lines);
    expect(g.map((x) => x.activity)).toEqual(['A01', 'A02']);
    expect(g[0]).toMatchObject({ amount: 1500, pct: 60, toDate: 900, period: 900 });
    expect(g[1]).toMatchObject({ amount: 2000, toDate: 200 });
  });
});

describe('issueBlock', () => {
  it('explains why a person cannot issue', () => {
    expect(issueBlock(undefined, PREVIEW)).toContain('Inicia sesión');
    expect(issueBlock('ESTIMATING', PREVIEW)).toBe('Estudios no emite certificaciones.');
    expect(issueBlock('SITE_MANAGER', PREVIEW)).toBe('');
    const blocked = { ...PREVIEW, result: { ...PREVIEW.result, errors: ['month 2027-05 is already certified'] } };
    expect(issueBlock('MANAGEMENT', blocked)).toContain('already certified');
  });
});

describe('Certifications view', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Certifications],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(Session).person.set({ username: 'obra', displayName: 'Ana Ríos', role: 'SITE_MANAGER', scope: [] });
  });

  afterEach(() => http.verify());

  async function render(issued: unknown[] = []) {
    const fixture = TestBed.createComponent(Certifications);
    fixture.componentRef.setInput('id', '3');
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne('/api/tenders/3/certification').flush(PREVIEW);
    http.expectOne('/api/tenders/3/certifications').flush(issued);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('shows the valuation by activity, the totals and no issued certification yet', async () => {
    const el = (await render()).nativeElement as HTMLElement;
    expect(el.querySelectorAll('tr.act').length).toBe(2);
    expect(el.querySelectorAll('tr.line').length).toBe(3);
    expect(el.querySelector('[data-testid="period"]')?.textContent).toContain('1.100,00');
    expect(el.querySelector('[data-testid="payable"]')?.textContent).toContain('1.264,45');
    expect(el.textContent).toContain('Todavía no se ha emitido ninguna');
    expect((el.querySelector('[data-testid="issue"]') as HTMLButtonElement).disabled).toBe(false);
  });

  it('issues the month the core previewed and reloads', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('[data-testid="issue"]') as HTMLButtonElement).click();
    const req = http.expectOne('/api/tenders/3/certifications');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ month: '2027-05' }); // the browser never sends an amount
    req.flush({ id: 1, number: 1, month: '2027-05', cutWeek: 3, budget: 3500, toDate: 1100, previous: 0, period: 1100,
      retention: 55, net: 1045, vat: 219.45, payable: 1264.45, issuedBy: 'obra', issuedAt: 't' });
    http.expectOne('/api/tenders/3/certification').flush(PREVIEW);
    http.expectOne('/api/tenders/3/certifications').flush([]);
    await fixture.whenStable();
    expect(fixture.componentInstance.feedback()).toContain('Certificación 1 emitida');
  });

  it('links every issued certification to its PDF, drawn by the core', async () => {
    const el = (await render([{ id: 1, number: 1, month: '2027-05', cutWeek: 3, budget: 3500, toDate: 1100, previous: 0,
      period: 1100, retention: 55, net: 1045, vat: 219.45, payable: 1264.45, issuedBy: 'obra', issuedAt: 't' }]))
      .nativeElement as HTMLElement;
    const link = el.querySelector('[data-testid="pdf-1"]') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/api/tenders/3/certifications/1/pdf');
    expect(link.hasAttribute('download')).toBe(true);
  });

  it('disables issuing for estimating and shows the core rules that block a month', async () => {
    TestBed.inject(Session).person.set({ username: 'estudios', displayName: 'Luis', role: 'ESTIMATING', scope: [] });
    const fixture = TestBed.createComponent(Certifications);
    fixture.componentRef.setInput('id', '3');
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne('/api/tenders/3/certification').flush({
      ...PREVIEW, result: { ...PREVIEW.result, errors: ['certify 2027-05 first: months are certified in order'] },
    });
    http.expectOne('/api/tenders/3/certifications').flush([]);
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect((el.querySelector('[data-testid="issue"]') as HTMLButtonElement).disabled).toBe(true);
    expect(el.querySelector('[data-testid="why-not"]')?.textContent).toContain('Estudios no emite');
    expect(el.querySelector('[data-testid="cert-errors"]')?.textContent).toContain('certify 2027-05 first');
  });
});
