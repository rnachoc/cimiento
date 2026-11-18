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
