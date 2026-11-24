import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Dashboard as DashboardData, PortfolioItem, Proposal, SiteView } from './api';
import { Dashboard, hoursText } from './dashboard/dashboard';
import { linePoints, Site } from './site/site';
import { TenderFile } from './tender/tender-file';

function proposal(id: number, type: string, status: Proposal['status'], payload: any, extra: Partial<Proposal> = {}): Proposal {
  return {
    id, tenderId: 7, tenderCode: 'E-001', type, status, route: 'HUMAN', payload, executedPayload: null, citations: [],
    signals: [], ruleResults: [], routingReasons: [], createdBy: 'AGENT', createdAt: '2026-11-18T10:00:00Z',
    decidedAt: null, ...extra,
  };
}

const ITEM: PortfolioItem = {
  tenderId: 7, code: 'E-001', title: 'Licitación E-001', projectCode: 'LICITACIONES', stage: 'UNDER_REVIEW',
  createdAt: '2026-11-18T10:00:00Z', stageChangedAt: null, bidDeadline: '2027-04-21', deadlineApproved: false,
  recommendation: 'BID', budgetTotal: 176597.12, pendingReviews: 3, proposals: 5, bidAnalysisApproved: false,
  next: ['DECISION_PENDING', 'DISCARDED'], revertTo: null, lastMovedBy: null,
};

const SITE: SiteView = {
  tenderId: 7, tenderCode: 'E-001', statusWeek: 3, cutDate: '2027-01-22', progressApproved: false, scheduleApproved: true,
  plannedEnd: '2027-03-01', forecastEnd: '2027-03-04', contractDeadline: '2027-03-03',
  analysis: {
    statusWeek: 3, plannedDuration: 40, forecastDuration: 43, impactDays: 3, impactIsLowerBound: true,
    curve: [{ week: 1, plannedPct: 10, actualPct: 10 }, { week: 2, plannedPct: 25, actualPct: 20 }, { week: 3, plannedPct: 40, actualPct: 30 }],
    deviations: [
      { activity: 'A01', name: 'Tierras', plannedPct: 100, actualPct: 100, delayDays: 0, delayIsLowerBound: false, totalFloat: 0, critical: true, alarm: false },
      { activity: 'A02', name: 'Estructura', plannedPct: 50, actualPct: 20, delayDays: 3, delayIsLowerBound: false, totalFloat: 0, critical: true, alarm: true },
      { activity: 'A05', name: 'Cubierta', plannedPct: 20, actualPct: 0, delayDays: 2, delayIsLowerBound: true, totalFloat: 6, critical: false, alarm: false },
    ],
  },
};

describe('new views', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('tender file gathers facts with citations, budget, schedule, risks and history', async () => {
    const fixture = TestBed.createComponent(TenderFile);
    fixture.componentRef.setInput('id', '7');
    fixture.detectChanges();
    http.expectOne('/api/portfolio/7').flush(ITEM);
    http.expectOne((r) => r.url === '/api/proposals' && r.params.get('tenderId') === '7').flush([
      // keys come back sorted (as the core stores them): citations must not be matched by position
      proposal(1, 'BID_ANALYSIS', 'PENDING_REVIEW', {
        fields: { anexo_plazo_ejecucion_meses: '7', penalidad_demora_eur_por_mil_dia: '0.6', plazo_ejecucion_meses: '8' },
        sources: { penalidad_demora_eur_por_mil_dia: { document: 'pliego', page: 1 }, anexo_plazo_ejecucion_meses: { document: 'anexo', page: 1 } },
        notFound: ['visita_obligatoria'] },
        { citations: [{ document: 'pliego', page: 1, text: 'Penalidad' }, { document: 'anexo', page: 1, text: 'Programa' }], signals: ['INJECTION_DETECTED'] }),
      proposal(2, 'SCHEDULE', 'EXECUTED', { activities: [{ code: 'A01', name: 'Tierras', duration: 5 }, { code: 'A02', name: 'Estructura', duration: 10 }],
        links: [{ from: 'A01', to: 'A02', lag: 0 }] }, { ruleResults: [{ rule: 'CRITICAL_PATH', outcome: 'PASS', message: '', details: { duration: 15, critical: ['A01', 'A02'] } }] }),
      proposal(3, 'RISK_SIGNAL', 'EXECUTED', { kind: 'CONTRADICTION', severity: 'HIGH', description: 'Plazos distintos' }),
      proposal(4, 'REPORT', 'PENDING_REVIEW', { kind: 'bid_no_bid', recommendation: 'BID' }),
      proposal(5, 'PROGRESS_UPDATE', 'PENDING_REVIEW', { week: 3, rows: [] }),
    ]);
    http.expectOne('/api/tenders/7/stages').flush([{ id: 1, from: 'TO_REVIEW', to: 'UNDER_REVIEW', note: null, actorRole: 'REVIEWER', at: '2026-11-18T11:00:00Z' }]);
    await fixture.whenStable();
    http.expectOne('/api/tenders/7/calendar').flush({ tenderId: 7, tenderCode: 'E-001', calendar: 'NONE', localHolidays: [],
      from: '2027-01-04', to: '2027-01-22', holidaysInSpan: [], durationDays: 15, endStandard: '2027-01-22', endWithCalendar: '2027-01-22' });
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const text = el.textContent ?? '';
    expect(el.querySelector('[data-testid="stage"]')?.textContent).toContain('En estudio');
    expect(el.querySelector('[data-testid="budget"]')?.textContent).toContain('176.597');
    expect(text).toContain('Plazo de ejecución');
    expect(text).toContain('8 meses');
    expect(el.querySelector('[data-testid="cite-anexo_plazo_ejecucion_meses"]')?.textContent).toContain('anexo · pág. 1');
    expect(el.querySelector('[data-testid="cite-penalidad_demora_eur_por_mil_dia"]')?.textContent).toContain('pliego · pág. 1');
    expect(el.querySelector('[data-testid="cite-plazo_ejecucion_meses"]')).toBeNull(); // no source given: no guess
    expect(text).toContain('0,6 € por cada 1.000 € y día');
    expect(text).toContain('Visita obligatoria'); // not found in the documents
    expect(text).toContain('Instrucción plantada detectada');
    expect(text).toContain('Plazos distintos');
    expect(text).toContain('Informe ofertar / no ofertar');
    expect(el.querySelectorAll('.g-bar').length).toBe(2);
    expect(el.querySelectorAll('.g-bar.crit').length).toBe(2);
    expect(el.querySelectorAll('[data-testid="history"] li').length).toBe(2);
    expect(el.querySelector('[data-testid="to-site"]')).not.toBeNull();
  });

  it('site view draws the curve and flags alarms and the end-date impact', async () => {
    const fixture = TestBed.createComponent(Site);
    fixture.componentRef.setInput('id', '7');
    fixture.detectChanges();
    http.expectOne('/api/tenders/7/site').flush(SITE);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="impact"]')?.textContent).toContain('≥ 3 días de retraso');
    expect(el.querySelector('[data-testid="alarms"]')?.textContent?.trim()).toBe('1');
    expect(el.querySelector('[data-testid="dev-A02"]')?.classList.contains('alarm')).toBe(true);
    expect(el.querySelector('[data-testid="dev-A05"]')?.textContent).toContain('≥ 2 d');
    expect(el.querySelector('[data-testid="actual-line"]')?.getAttribute('points')?.split(' ').length).toBe(3);
    expect(el.textContent).toContain('pendientes de aprobación');
    expect(el.textContent).toContain('el fin previsto lo supera');
  });

  it('site view explains when there is nothing to show', async () => {
    const fixture = TestBed.createComponent(Site);
    fixture.componentRef.setInput('id', '8');
    fixture.detectChanges();
    http.expectOne('/api/tenders/8/site').flush({}, { status: 404, statusText: 'Not found' });
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent).toContain('aún no tiene');
  });

  it('curve points scale weeks to the width and percent to the height', () => {
    const pts = linePoints([{ week: 1, plannedPct: 0, actualPct: 0 }, { week: 2, plannedPct: 100, actualPct: 50 }], 'plannedPct', 2);
    const [[x1, y1], [x2, y2]] = pts.split(' ').map((p) => p.split(',').map(Number));
    expect(x1).toBeLessThan(x2);
    expect(y1).toBeGreaterThan(y2); // 0 % is at the bottom
  });

  it('dashboard shows money, win rate, funnel, deadlines and decisions', async () => {
    const data: DashboardData = {
      tendersByStage: { TO_REVIEW: 2, UNDER_REVIEW: 1, DECISION_PENDING: 0, IN_PREPARATION: 1, SUBMITTED: 0, AWARDED: 1, LOST: 1, DISCARDED: 1 },
      open: 4, winRatePct: 50, openBudget: 1250000, awardedBudget: 300000, meanMinutesInStage: { TO_REVIEW: 1800, UNDER_REVIEW: 12 },
      pendingByType: { BUDGET: 4, SCHEDULE: 2 }, oldestPendingHours: 72, decisionsByOutcome: { APPROVE: 5, CORRECT: 1 },
      executedAutomatically: 3, executedByPerson: 6, rulesFailed: 0,
      upcomingDeadlines: [{ code: 'E-002', date: '2027-01-07', daysLeft: -2 }, { code: 'E-004', date: '2027-01-20', daysLeft: 11 }],
    };
    const fixture = TestBed.createComponent(Dashboard);
    http.expectOne('/api/dashboard').flush(data);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="open"]')?.textContent?.trim()).toBe('4');
    expect(el.querySelector('[data-testid="win-rate"]')?.textContent?.trim()).toBe('50 %');
    expect(el.querySelector('[data-testid="open-budget"]')?.textContent).toContain('1.250.000');
    expect(el.querySelectorAll('[data-testid="funnel"] .b-row').length).toBe(8);
    expect(el.querySelector('[data-testid="deadlines"]')?.textContent).toContain('vencida hace 2 días');
    expect(el.textContent).toContain('33 %'); // 3 automatic of 9 executed
    expect(el.textContent).toContain('3 días'); // oldest pending: 72 h
  });

  it('hours read as minutes, hours or days', () => {
    expect(hoursText(0.2)).toBe('12 min');
    expect(hoursText(0.001)).toBe('< 1 min');
    expect(hoursText(30)).toBe('30 h');
    expect(hoursText(72)).toBe('3 días');
    expect(hoursText(null)).toBe('—');
  });
});
