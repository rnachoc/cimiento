import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Proposal } from '../api';
import { ProposalDetail, scheduleBars } from './proposal-detail';

const SCHEDULE: Proposal = {
  id: 9, tenderId: 1, tenderCode: 'E-002', type: 'SCHEDULE', status: 'PENDING_REVIEW', route: 'HUMAN',
  payload: {
    activities: [{ code: 'A01', name: 'Tierras', duration: 5 }, { code: 'A02', name: 'Cimentación', duration: 10 },
                 { code: 'A03', name: 'Cubierta', duration: 2 }],
    links: [{ from: 'A01', to: 'A02', lag: 0 }, { from: 'A01', to: 'A03', lag: 0 }],
  },
  executedPayload: null,
  citations: [{ document: 'mediciones', page: 1, text: 'MT01 | Excavación' }],
  signals: ['INJECTION_DETECTED'],
  ruleResults: [
    { rule: 'SCHEDULE_GRAPH', outcome: 'PASS', message: 'valid acyclic graph', details: {} },
    { rule: 'CRITICAL_PATH', outcome: 'PASS', message: 'duration 15', details: { duration: 15, critical: ['A01', 'A02'] } },
    { rule: 'CONTRACT_TERM', outcome: 'WARN', message: 'late', details: {} },
  ],
  routingReasons: ['TYPE_REQUIRES_REVIEW', 'INJECTION_DETECTED'],
  createdBy: 'AGENT', createdAt: '2026-11-18T10:00:00Z', decidedAt: null,
};

describe('scheduleBars', () => {
  it('places bars by early start and marks the critical path from the core', () => {
    const bars = scheduleBars(SCHEDULE);
    const a02 = bars.find((b) => b.code === 'A02')!;
    expect(a02.start).toBeCloseTo((5 / 15) * 100);
    expect(a02.width).toBeCloseTo((10 / 15) * 100);
    expect(bars.filter((b) => b.critical).map((b) => b.code)).toEqual(['A01', 'A02']);
    expect(scheduleBars({ ...SCHEDULE, type: 'BUDGET' })).toEqual([]);
  });
});

describe('ProposalDetail', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ProposalDetail],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  async function render() {
    const fixture = TestBed.createComponent(ProposalDetail);
    fixture.componentRef.setInput('id', '9');
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectOne('/api/proposals/9').flush(SCHEDULE);
    http.expectOne('/api/audit').flush([
      { seq: 1, createdAt: 't', actorRole: 'AGENT', action: 'PROPOSAL_SUBMITTED', entityType: 'proposal', entityId: '9', details: {}, hash: 'a'.repeat(64) },
      { seq: 2, createdAt: 't', actorRole: 'AGENT', action: 'PROPOSAL_SUBMITTED', entityType: 'proposal', entityId: '10', details: {}, hash: 'b'.repeat(64) },
    ]);
    await fixture.whenStable();
    return fixture;
  }

  it('shows facts, rules, citations, audit and the injection banner', async () => {
    const el = (await render()).nativeElement as HTMLElement;
    expect(el.querySelectorAll('.g-bar').length).toBe(3);
    expect(el.querySelectorAll('.g-bar.crit').length).toBe(2);
    expect([...el.querySelectorAll('.rules strong')].map((s) => s.textContent)).toEqual(['SCHEDULE_GRAPH', 'CRITICAL_PATH', 'CONTRACT_TERM']);
    expect(el.querySelector('.cite')?.textContent).toContain('mediciones · pág. 1');
    expect(el.querySelectorAll('.audit li').length).toBe(1);
    expect(el.querySelector('[role="note"]')?.textContent).toContain('no se ha obedecido');
    expect(el.querySelector('[data-testid="status"]')?.textContent).toContain('Pendiente');
  });

  it('approves through the API and shows the executed status', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('[data-testid="approve"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    const req = http.expectOne('/api/proposals/9/decision');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ outcome: 'APPROVE', comment: '' });
    expect(req.request.headers.has('X-Api-Key')).toBe(false); // the browser never sends a key
    req.flush({ ...SCHEDULE, status: 'EXECUTED' });
    http.expectOne('/api/audit').flush([]);
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="status"]')?.textContent).toContain('Ejecutada');
    expect(el.querySelector('[data-testid="approve"]')).toBeNull();
  });

  it('rejects an invalid corrected payload before calling the API', async () => {
    const fixture = await render();
    const cmp = fixture.componentInstance;
    cmp.outcome.set('CORRECT');
    cmp.corrected = '{not json';
    cmp.submit();
    expect(cmp.feedback()).toContain('no es un JSON válido');
    http.expectNone('/api/proposals/9/decision');
  });
});
