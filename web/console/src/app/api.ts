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
}
export interface AuditEntry {
  seq: number; createdAt: string; actorRole: string; action: string;
  entityType: string; entityId: string; details: Record<string, unknown>; hash: string;
}
export type Outcome = 'APPROVE' | 'CORRECT' | 'REJECT';
export type Stage = 'TO_REVIEW' | 'UNDER_REVIEW' | 'DECISION_PENDING' | 'IN_PREPARATION' | 'SUBMITTED'
  | 'AWARDED' | 'LOST' | 'DISCARDED';
export interface PortfolioItem {
  tenderId: number; code: string; title: string; projectCode: string; stage: Stage; stageChangedAt: string | null;
  bidDeadline: string | null; deadlineApproved: boolean; recommendation: string | null;
  pendingReviews: number; proposals: number; bidAnalysisApproved: boolean; next: Stage[];
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
