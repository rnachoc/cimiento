import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Shapes returned by the core API (see services/core ApiDtos). */
export interface Citation { document: string; page: number; text: string; }
export interface RuleResult { rule: string; outcome: 'PASS' | 'WARN' | 'FAIL' | 'SKIP'; message: string; details: Record<string, unknown>; }
export interface Proposal {
  id: number;
  tenderId: number;
  tenderCode: string;
  type: string;
  status: 'RULES_FAILED' | 'PENDING_REVIEW' | 'EXECUTED' | 'REJECTED';
  route: 'AUTO' | 'HUMAN' | null;
  payload: any;
  executedPayload: any;
  citations: Citation[];
  signals: string[];
  ruleResults: RuleResult[];
  routingReasons: string[];
  createdBy: string;
  createdAt: string;
  decidedAt: string | null;
  /** Four eyes: a correction waiting for a second person. */
  correctedPayload?: any;
  correctedBy?: string | null;
  decisions?: { outcome: Outcome; person: string | null; at: string }[];
}
export interface AuditEntry {
  seq: number; createdAt: string; actorRole: string; actorPerson?: string | null; action: string;
  entityType: string; entityId: string; details: Record<string, unknown>; hash: string;
}
export type Outcome = 'APPROVE' | 'CORRECT' | 'REJECT';
export type Stage = 'TO_REVIEW' | 'UNDER_REVIEW' | 'DECISION_PENDING' | 'IN_PREPARATION' | 'SUBMITTED'
  | 'AWARDED' | 'LOST' | 'DISCARDED';
export interface PortfolioItem {
  tenderId: number; code: string; title: string; projectCode: string; stage: Stage; createdAt: string;
  stageChangedAt: string | null; bidDeadline: string | null; deadlineApproved: boolean; recommendation: string | null;
  budgetTotal: number | null; pendingReviews: number; proposals: number; bidAnalysisApproved: boolean; next: Stage[];
  revertTo: Stage | null; lastMovedBy: string | null;
}
export interface StageChange { id: number; from: Stage; to: Stage; note: string | null; actorRole: string; person?: string | null; at: string; revert?: boolean; }
export type CalendarKind = 'NONE' | 'ES_NATIONAL';
export interface Holiday { date: string; name: string; }
export interface CalendarView {
  tenderId: number; tenderCode: string; calendar: CalendarKind; localHolidays: Holiday[]; from: string; to: string;
  holidaysInSpan: Holiday[]; durationDays: number | null; endStandard: string | null; endWithCalendar: string | null;
}
export type PersonRole = 'ESTIMATING' | 'SITE_MANAGER' | 'MANAGEMENT';
export interface Person { username: string; displayName: string; role: PersonRole; scope: string[]; }
export interface CurvePoint { week: number; plannedPct: number; actualPct: number; }
export interface Deviation {
  activity: string; name: string; plannedPct: number; actualPct: number; delayDays: number;
  delayIsLowerBound: boolean; totalFloat: number; critical: boolean; alarm: boolean;
}
export interface SiteView {
  tenderId: number; tenderCode: string; statusWeek: number; cutDate: string; progressApproved: boolean;
  scheduleApproved: boolean; plannedEnd: string; forecastEnd: string; contractDeadline: string | null;
  analysis: { statusWeek: number; curve: CurvePoint[]; deviations: Deviation[]; plannedDuration: number;
    forecastDuration: number; impactDays: number; impactIsLowerBound: boolean; };
}
export interface CertLine {
  code: string; activity: string; description: string; amount: number; pct: number;
  toDate: number; previous: number; period: number;
}
export interface CertResult {
  month: string; expectedMonth: string; cutWeek: number | null; cutDate: string | null; lines: CertLine[];
  budgetTotal: number; toDate: number; previous: number; period: number; retention: number; net: number;
  vat: number; payable: number; errors: string[];
}
export interface CertPreview {
  tenderId: number; tenderCode: string; budgetApproved: boolean; progressApproved: boolean; result: CertResult;
}
export interface CertIssued {
  id: number; number: number; month: string; cutWeek: number; budget: number; toDate: number; previous: number;
  period: number; retention: number; net: number; vat: number; payable: number; issuedBy: string; issuedAt: string;
}
export interface Dashboard {
  tendersByStage: Record<Stage, number>; open: number; winRatePct: number | null; openBudget: number;
  awardedBudget: number; meanMinutesInStage: Partial<Record<Stage, number>>; pendingByType: Record<string, number>;
  oldestPendingHours: number | null; decisionsByOutcome: Record<string, number>; executedAutomatically: number;
  executedByPerson: number; rulesFailed: number; upcomingDeadlines: { code: string; date: string; daysLeft: number }[];
}

/**
 * The browser never holds credentials: requests go to /api on the same origin and nginx adds
 * the REVIEWER key on the server side.
 */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  proposals(status?: string): Observable<Proposal[]> {
    return this.http.get<Proposal[]>('/api/proposals', { params: status ? { status } : {} });
  }

  proposal(id: number): Observable<Proposal> {
    return this.http.get<Proposal>(`/api/proposals/${id}`);
  }

  audit(): Observable<AuditEntry[]> {
    return this.http.get<AuditEntry[]>('/api/audit');
  }

  portfolio(): Observable<PortfolioItem[]> {
    return this.http.get<PortfolioItem[]>('/api/portfolio');
  }

  portfolioItem(tenderId: number): Observable<PortfolioItem> {
    return this.http.get<PortfolioItem>(`/api/portfolio/${tenderId}`);
  }

  tenderProposals(tenderId: number): Observable<Proposal[]> {
    return this.http.get<Proposal[]>('/api/proposals', { params: { tenderId } });
  }

  stages(tenderId: number): Observable<StageChange[]> {
    return this.http.get<StageChange[]>(`/api/tenders/${tenderId}/stages`);
  }

  calendar(tenderId: number): Observable<CalendarView> {
    return this.http.get<CalendarView>(`/api/tenders/${tenderId}/calendar`);
  }

  setCalendar(tenderId: number, calendar: CalendarKind, localHolidays: Holiday[]): Observable<CalendarView> {
    return this.http.post<CalendarView>(`/api/tenders/${tenderId}/calendar`, { calendar, localHolidays });
  }

  revertStage(tenderId: number, note: string): Observable<PortfolioItem> {
    return this.http.post<PortfolioItem>(`/api/tenders/${tenderId}/stage/revert`, { note });
  }

  site(tenderId: number): Observable<SiteView> {
    return this.http.get<SiteView>(`/api/tenders/${tenderId}/site`);
  }

  certification(tenderId: number, month?: string): Observable<CertPreview> {
    return this.http.get<CertPreview>(`/api/tenders/${tenderId}/certification`, { params: month ? { month } : {} });
  }

  certifications(tenderId: number): Observable<CertIssued[]> {
    return this.http.get<CertIssued[]>(`/api/tenders/${tenderId}/certifications`);
  }

  issueCertification(tenderId: number, month: string): Observable<CertIssued> {
    return this.http.post<CertIssued>(`/api/tenders/${tenderId}/certifications`, { month });
  }

  dashboard(): Observable<Dashboard> {
    return this.http.get<Dashboard>('/api/dashboard');
  }

  moveStage(tenderId: number, stage: Stage, note: string): Observable<PortfolioItem> {
    return this.http.post<PortfolioItem>(`/api/tenders/${tenderId}/stage`, note.trim() ? { stage, note } : { stage });
  }

  /** Same-origin link: nginx adds the key, the browser downloads the file. */
  exportUrl(id: number): string {
    return `/api/proposals/${id}/export`;
  }

  decide(id: number, outcome: Outcome, comment: string, correctedPayload?: unknown): Observable<Proposal> {
    const body: Record<string, unknown> = { outcome, comment };
    if (outcome === 'CORRECT') {
      body['correctedPayload'] = correctedPayload;
    }
    return this.http.post<Proposal>(`/api/proposals/${id}/decision`, body);
  }
}

export const ROLE_LABELS: Record<PersonRole, string> = {
  ESTIMATING: 'Estudios',
  SITE_MANAGER: 'Jefatura de obra',
  MANAGEMENT: 'Gerencia',
};

/** Stages only management may move a tender to (the core enforces the same list). */
export const MANAGEMENT_STAGES: Stage[] = ['IN_PREPARATION', 'DISCARDED'];

/**
 * Why this person may not take a decision, mirroring rules/Authority in the core (the core
 * decides; this only explains a disabled button). Empty string: allowed.
 */
export function decisionBlock(person: Person | null, p: Proposal, outcome: Outcome): string {
  if (!person) return 'Inicia sesión para decidir.';
  if (!person.scope.includes(p.type)) return `${ROLE_LABELS[person.role]} no decide ${TYPE_LABELS[p.type] ?? p.type}.`;
  if (p.routingReasons.includes('AMOUNT_OVER_THRESHOLD') && person.role !== 'MANAGEMENT') {
    return 'El importe supera el umbral: decide gerencia.';
  }
  if (outcome === 'CORRECT' && p.correctedBy) return 'Ya hay una corrección esperando segunda aprobación.';
  if (outcome === 'APPROVE' && p.correctedBy === person.username) {
    return 'Corregiste esta propuesta: la aprueba otra persona.';
  }
  return '';
}

export const TYPE_LABELS: Record<string, string> = {
  BID_ANALYSIS: 'Análisis del pliego',
  TAKEOFF: 'Mediciones',
  BUDGET: 'Presupuesto',
  SCHEDULE: 'Planning',
  RISK_SIGNAL: 'Riesgo',
  REPORT: 'Informe',
  DOCUMENT_DRAFT: 'Memoria técnica',
  PROGRESS_UPDATE: 'Avance de obra',
};

export const STAGE_LABELS: Record<Stage, string> = {
  TO_REVIEW: 'Por revisar',
  UNDER_REVIEW: 'En estudio',
  DECISION_PENDING: 'Decisión pendiente',
  IN_PREPARATION: 'Preparando oferta',
  SUBMITTED: 'Presentada',
  AWARDED: 'Adjudicada',
  LOST: 'No adjudicada',
  DISCARDED: 'Descartada',
};

/** Stages that need a written reason (the core enforces the same list). */
export const NOTE_REQUIRED: Stage[] = ['IN_PREPARATION', 'DISCARDED', 'LOST'];

/** Euros with Spanish formatting, no decimals for large amounts. */
export function euros(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = Number(v);
  return new Intl.NumberFormat('es-ES', { style: 'currency', currency: 'EUR', maximumFractionDigits: n >= 10000 ? 0 : 2 }).format(n);
}
