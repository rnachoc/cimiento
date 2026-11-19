import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, euros, PortfolioItem, Proposal, STAGE_LABELS, StageChange, TYPE_LABELS } from '../api';
import { daysLeft } from '../portfolio/portfolio';
import { scheduleBars } from '../proposal/proposal-detail';

const FIELD_LABELS: Record<string, string> = {
  presupuesto_base_eur: 'Presupuesto base',
  plazo_ejecucion_meses: 'Plazo de ejecución',
  anexo_plazo_ejecucion_meses: 'Plazo del programa (anexo)',
  fecha_presentacion: 'Fecha de presentación',
  visita_obligatoria: 'Visita obligatoria',
  criterio_precio_pct: 'Criterio precio',
  criterio_plazo_pct: 'Criterio plazo',
  criterio_mejoras_pct: 'Criterio mejoras',
  garantia_definitiva_pct: 'Garantía definitiva',
  penalidad_demora_eur_por_mil_dia: 'Penalidad por demora',
  solvencia_economica_eur: 'Solvencia económica',
  solvencia_tecnica_obras: 'Obras similares exigidas',
  subcontratacion_max_pct: 'Subcontratación máxima',
};

const REPORT_LABELS: Record<string, string> = {
  bid_no_bid: 'Informe ofertar / no ofertar', rfi: 'Solicitud de aclaraciones (RFI)', informe_semanal: 'Informe semanal',
  acta: 'Acta de reunión', parte_diario: 'Parte diario', memoria_tecnica: 'Memoria técnica',
};

interface Source { document: string; page: number; }
interface Fact { key: string; label: string; value: string; cite: Source | null; }

/** Everything about one tender on one page: what the documents say, what the agents propose, what the core decided. */
@Component({
  selector: 'app-tender-file',
  imports: [RouterLink],
  template: `
    <a routerLink="/cartera" class="back">← Cartera</a>
    @if (error()) { <p class="error glass" role="alert">{{ error() }}</p> }
    @if (item(); as t) {
      <header class="head reveal">
        <p class="eyebrow">Ficha de licitación · {{ t.projectCode }}</p>
        <h1 data-testid="tender-title">{{ t.code }} <span class="muted">{{ t.title }}</span></h1>
        <div class="badges">
          <span class="pill" [class.ok]="t.stage === 'AWARDED'" [class.fail]="t.stage === 'LOST' || t.stage === 'DISCARDED'"
                data-testid="stage">{{ stageLabel(t.stage) }}</span>
          @if (t.recommendation) {
            <span class="pill" [class.ok]="t.recommendation === 'BID'" [class.fail]="t.recommendation === 'NO_BID'"
                  [class.warn]="t.recommendation === 'REVIEW'">Recomendación: {{ recLabel(t.recommendation) }}</span>
          }
          @if (injected()) { <span class="pill fail">Instrucción plantada detectada</span> }
        </div>
        <div class="kpis">
          <div class="kpi glass"><span class="k">Presentación</span>
            <span class="v" data-testid="deadline">{{ t.bidDeadline ?? '—' }}</span>
            @if (t.bidDeadline) { <span class="s">{{ countdown(t.bidDeadline) }}{{ t.deadlineApproved ? '' : ' · sin aprobar' }}</span> }
          </div>
          <div class="kpi glass"><span class="k">Presupuesto calculado</span>
            <span class="v" data-testid="budget">{{ money(t.budgetTotal) }}</span><span class="s">sin IVA, tarifas de la empresa</span></div>
          <div class="kpi glass"><span class="k">Planning</span>
            <span class="v">{{ duration() ?? '—' }}</span><span class="s">días laborables</span></div>
          <div class="kpi glass"><span class="k">Por revisar</span>
            <span class="v">{{ t.pendingReviews }}</span><span class="s">de {{ t.proposals }} propuestas</span></div>
        </div>
        @if (hasSite()) {
          <a class="btn btn-ghost site" [routerLink]="['/obra', t.tenderId]" data-testid="to-site">Ver seguimiento de obra →</a>
          <a class="btn btn-ghost site" [routerLink]="['/certificaciones', t.tenderId]" data-testid="to-certifications">Certificaciones →</a>
        }
      </header>

      <div class="grid">
        <section class="panel glass reveal" aria-labelledby="f-facts">
          <h2 id="f-facts">Lo que dicen los pliegos</h2>
          @if (facts().length) {
            <dl class="facts">
              @for (f of facts(); track f.key) {
                <dt>{{ f.label }}</dt>
                <dd>{{ f.value }} @if (f.cite) { <span class="cite" [attr.data-testid]="'cite-' + f.key">{{ f.cite.document }} · pág. {{ f.cite.page }}</span> }</dd>
              }
            </dl>
          } @else { <p class="muted">Sin análisis del pliego.</p> }
          @if (notFound().length) {
            <p class="muted small">No consta en los documentos: {{ notFound().join(', ') }}</p>
          }
        </section>

        <section class="panel glass reveal" aria-labelledby="f-risks">
          <h2 id="f-risks">Riesgos</h2>
          @for (r of risks(); track r.id) {
            <a class="risk" [routerLink]="['/proposals', r.id]" [class.high]="r.payload?.severity === 'HIGH'">
              <span class="pill" [class.fail]="r.payload?.severity === 'HIGH'" [class.warn]="r.payload?.severity === 'MEDIUM'">{{ sevLabel(r.payload?.severity) }}</span>
              <span>{{ r.payload?.description }}</span>
            </a>
          } @empty { <p class="muted">Sin señales de riesgo.</p> }
        </section>

        <section class="panel glass reveal wide" aria-labelledby="f-plan">
          <h2 id="f-plan">Planning y ruta crítica</h2>
          @if (bars().length) {
            <div class="gantt" role="img" [attr.aria-label]="'Planning de ' + bars().length + ' actividades; en verde la ruta crítica'">
              @for (b of bars(); track b.code) {
                <div class="g-row">
                  <span class="g-label">{{ b.code }} <small>{{ b.name }}</small></span>
                  <span class="g-lane"><span class="g-bar" [class.crit]="b.critical" [style.left.%]="b.start" [style.width.%]="b.width"></span></span>
                </div>
              }
            </div>
          } @else { <p class="muted">Sin planning propuesto.</p> }
        </section>

        <section class="panel glass reveal" aria-labelledby="f-docs">
          <h2 id="f-docs">Informes y borradores</h2>
          <ul class="list">
            @for (d of documents(); track d.id) {
              <li><a [routerLink]="['/proposals', d.id]">{{ docLabel(d) }}</a>&nbsp; <span class="pill" [class.ok]="d.status === 'EXECUTED'">{{ statusLabel(d.status) }}</span></li>
            } @empty { <li class="muted">Ninguno.</li> }
          </ul>
        </section>

        <section class="panel glass reveal" aria-labelledby="f-history">
          <h2 id="f-history">Historial de etapas</h2>
          <ol class="timeline" data-testid="history">
            <li><span class="dot"></span><b>{{ stageLabel('TO_REVIEW') }}</b><span class="muted">&nbsp;· {{ when(t.createdAt) }}</span></li>
            @for (h of history(); track h.id) {
              <li><span class="dot"></span><b>{{ stageLabel(h.to) }}</b><span class="muted">&nbsp;· {{ when(h.at) }}@if (h.person) { · {{ h.person }} }</span>
                @if (h.note) { <q>{{ h.note }}</q> }</li>
            }
          </ol>
        </section>

        <section class="panel glass reveal wide" aria-labelledby="f-props">
          <h2 id="f-props">Todas las propuestas ({{ proposals().length }})</h2>
          <table>
            <thead><tr><th>#</th><th>Tipo</th><th>Estado</th><th>Motivos</th></tr></thead>
            <tbody>
              @for (p of proposals(); track p.id) {
                <tr><td><a [routerLink]="['/proposals', p.id]">#{{ p.id }}</a></td><td>{{ typeLabel(p.type) }}</td>
                  <td><span class="pill" [class.ok]="p.status === 'EXECUTED'" [class.fail]="p.status === 'RULES_FAILED' || p.status === 'REJECTED'"
                            [class.warn]="p.status === 'PENDING_REVIEW'">{{ statusLabel(p.status) }}</span></td>
                  <td class="muted">{{ p.routingReasons.join(', ') }}</td></tr>
              }
            </tbody>
          </table>
        </section>
      </div>
    }
  `,
  styles: `
    .back { display: inline-block; margin: 32px 8px 0; color: var(--muted); text-decoration: none; }
    .head { padding: 24px 8px 8px; }
    h1 { font-size: clamp(2rem, 4.5vw, 3.6rem); letter-spacing: -0.035em; line-height: 1.05; margin: 10px 0 16px; }
    h1 .muted { color: var(--muted); font-weight: 700; }
    .badges { display: flex; flex-wrap: wrap; gap: 8px; }
    .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 14px; margin-top: 24px; }
    .kpi { padding: 16px 20px; display: flex; flex-direction: column; gap: 2px; }
    .k { color: var(--muted); font-size: .85rem; }
    .v { font-size: 1.7rem; font-weight: 800; letter-spacing: -0.02em; }
    .s { color: var(--muted); font-size: .82rem; }
    .site { display: inline-flex; margin-top: 18px; text-decoration: none; }
    .grid { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(0, 1fr); gap: 18px; margin: 24px 0; }
    @media (max-width: 900px) { .grid { grid-template-columns: 1fr; } }
    .panel { padding: 24px; min-width: 0; }
    .wide { grid-column: 1 / -1; }
    h2 { margin: 0 0 16px; font-size: 1.15rem; }
    .facts { display: grid; grid-template-columns: max-content 1fr; gap: 8px 18px; margin: 0; }
    dt { color: var(--muted); } dd { margin: 0; font-weight: 600; }
    .cite { margin-left: 8px; font-size: .75rem; color: var(--accent); font-weight: 600; white-space: nowrap; }
    @media (max-width: 520px) { .facts { grid-template-columns: 1fr; } dt { margin-top: 6px; } }
    .small { font-size: .85rem; margin-top: 14px; }
    .risk { display: flex; gap: 10px; align-items: flex-start; padding: 10px 0; border-bottom: 1px solid var(--border); text-decoration: none; }
    .gantt { display: grid; gap: 8px; }
    .g-row { display: grid; grid-template-columns: 190px 1fr; align-items: center; gap: 10px; }
    @media (max-width: 520px) { .g-row { grid-template-columns: 110px 1fr; } .g-label small { display: none; } }
    .g-label { font-weight: 700; font-size: .9rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .g-label small { color: var(--muted); font-weight: 400; }
    .g-lane { position: relative; height: 16px; background: var(--surface); border-radius: 999px; }
    .g-bar { position: absolute; top: 0; height: 100%; border-radius: 999px; background: linear-gradient(90deg, var(--accent-2), #a993ff); }
    .g-bar.crit { background: linear-gradient(90deg, var(--accent), #b6ff8f); box-shadow: 0 0 18px rgba(83,252,24,.45); }
    .list { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
    .list a { font-weight: 600; }
    .timeline { list-style: none; margin: 0; padding: 0 0 0 6px; border-left: 2px solid var(--border); display: grid; gap: 14px; }
    .timeline li { position: relative; padding-left: 18px; }
    .dot { position: absolute; left: -7px; top: 6px; width: 12px; height: 12px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 12px rgba(83,252,24,.6); }
    q { display: block; color: var(--muted); font-style: italic; margin-top: 4px; }
    table { width: 100%; border-collapse: collapse; font-size: .92rem; }
    th, td { text-align: left; padding: 8px; border-bottom: 1px solid var(--border); }
    th { color: var(--muted); font-weight: 600; }
    @media (max-width: 620px) { th:nth-child(4), td:nth-child(4) { display: none; } }
    .muted { color: var(--muted); }
    .error { padding: 20px 24px; margin: 24px 8px; border-color: rgba(255,61,113,.6); }
  `,
})
export class TenderFile {
  private readonly api = inject(Api);
  readonly id = input.required<string>();
  readonly item = signal<PortfolioItem | null>(null);
  readonly proposals = signal<Proposal[]>([]);
  readonly history = signal<StageChange[]>([]);
  readonly error = signal<string | null>(null);

  private latest(type: string): Proposal | undefined {
    return [...this.proposals()].reverse().find((p) => p.type === type && (p.status === 'PENDING_REVIEW' || p.status === 'EXECUTED'));
  }

  readonly bid = computed(() => this.latest('BID_ANALYSIS'));
  readonly schedule = computed(() => this.latest('SCHEDULE'));
  readonly bars = computed(() => scheduleBars(this.schedule() ?? null));
  readonly duration = computed(() => {
    const cp = this.schedule()?.ruleResults.find((r) => r.rule === 'CRITICAL_PATH');
    return (cp?.details?.['duration'] as number | undefined) ?? null;
  });
  readonly hasSite = computed(() => this.proposals().some((p) => p.type === 'PROGRESS_UPDATE' && p.status !== 'RULES_FAILED' && p.status !== 'REJECTED'));
  readonly injected = computed(() => this.proposals().some((p) => p.signals.includes('INJECTION_DETECTED')));
  readonly risks = computed(() => this.proposals().filter((p) => p.type === 'RISK_SIGNAL' && p.status !== 'REJECTED' && p.status !== 'RULES_FAILED'));
  readonly documents = computed(() => this.proposals().filter((p) => p.type === 'REPORT' || p.type === 'DOCUMENT_DRAFT'));
  readonly notFound = computed<string[]>(() => (this.content(this.bid())?.notFound ?? []).map((k: string) => this.fieldLabel(k)));
  readonly facts = computed<Fact[]>(() => {
    const c = this.content(this.bid());
    const fields: Record<string, unknown> = c?.fields ?? {};
    // each field names its own source; without one, no citation is shown rather than a guessed one
    const sources: Record<string, Source> = c?.sources ?? {};
    const rank = (k: string) => (Object.keys(FIELD_LABELS).indexOf(k) + 1) || 999;
    return Object.entries(fields)
      .sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b))
      .map(([key, value]) => ({ key, label: this.fieldLabel(key), value: this.fieldValue(key, value), cite: sources[key] ?? null }));
  });

  constructor() {
    effect(() => this.load(Number(this.id())));
  }

  private load(id: number): void {
    this.api.portfolioItem(id).subscribe({
      next: (t) => this.item.set(t),
      error: (e) => this.error.set(e?.status === 404 ? 'La licitación no existe.' : 'No se pudo cargar la licitación.'),
    });
    this.api.tenderProposals(id).subscribe({ next: (ps) => this.proposals.set(ps) });
    this.api.stages(id).subscribe({ next: (h) => this.history.set(h) });
  }

  private content(p: Proposal | undefined): any {
    return p ? (p.executedPayload ?? p.payload) : null;
  }

  fieldLabel(k: string): string {
    return FIELD_LABELS[k] ?? k.replace(/_/g, ' ');
  }

  fieldValue(k: string, v: unknown): string {
    const s = String(v);
    if (k.endsWith('_eur')) return euros(s);
    const n = /^-?\d+(\.\d+)?$/.test(s) ? Number(s).toLocaleString('es-ES') : s;
    if (k.endsWith('_pct')) return `${n} %`;
    if (k.endsWith('_meses')) return `${n} meses`;
    if (k === 'penalidad_demora_eur_por_mil_dia') return `${n} € por cada 1.000 € y día`;
    if (k === 'visita_obligatoria') return s.startsWith('si') ? `Sí (${s.split(':')[1] ?? ''})` : 'No';
    return n;
  }

  money(v: number | null): string { return euros(v); }
  stageLabel(s: keyof typeof STAGE_LABELS): string { return STAGE_LABELS[s]; }
  typeLabel(t: string): string { return TYPE_LABELS[t] ?? t; }
  recLabel(r: string): string { return ({ BID: 'ofertar', NO_BID: 'no ofertar', REVIEW: 'revisar' } as Record<string, string>)[r] ?? r; }
  sevLabel(s: string): string { return ({ HIGH: 'Alto', MEDIUM: 'Medio', LOW: 'Bajo' } as Record<string, string>)[s] ?? s; }
  statusLabel(s: Proposal['status']): string {
    return { RULES_FAILED: 'Rechazada por reglas', PENDING_REVIEW: 'Pendiente', EXECUTED: 'Ejecutada', REJECTED: 'Rechazada' }[s];
  }
  docLabel(p: Proposal): string {
    const pl = this.content(p) ?? {};
    return REPORT_LABELS[pl.kind] ?? pl.title ?? this.typeLabel(p.type);
  }
  countdown(date: string): string {
    const d = daysLeft(date);
    return d === 0 ? 'hoy' : d > 0 ? `en ${d} días` : `hace ${-d} días`;
  }
  when(iso: string): string {
    return new Date(iso).toLocaleString('es-ES', { dateStyle: 'medium', timeStyle: 'short' });
  }
}
