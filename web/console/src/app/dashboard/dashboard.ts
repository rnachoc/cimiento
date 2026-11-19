import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, Dashboard as DashboardData, euros, Stage, STAGE_LABELS, TYPE_LABELS } from '../api';

const ORDER: Stage[] = ['TO_REVIEW', 'UNDER_REVIEW', 'DECISION_PENDING', 'IN_PREPARATION', 'SUBMITTED', 'AWARDED', 'LOST', 'DISCARDED'];

interface Bar { label: string; value: number; pct: number; text: string; tone?: string; }

/** Hours as a short Spanish duration. */
export function hoursText(h: number | null | undefined): string {
  if (h === null || h === undefined) return '—';
  if (h * 60 < 1) return '< 1 min';
  if (h < 1) return `${Math.round(h * 60)} min`;
  if (h < 48) return `${Math.round(h * 10) / 10} h`;
  return `${Math.round(h / 24)} días`;
}

function bars(entries: [string, number][], text: (v: number) => string = String): Bar[] {
  const max = Math.max(1, ...entries.map(([, v]) => v));
  return entries.map(([label, value]) => ({ label, value, pct: (value / max) * 100, text: text(value) }));
}

/** Management view: pipeline, money, win rate, review load and how people decide. */
@Component({
  selector: 'app-dashboard',
  imports: [RouterLink],
  template: `
    <section class="hero reveal" aria-labelledby="d-title">
      <p class="eyebrow">Dirección</p>
      <h1 id="d-title">La cartera de un vistazo</h1>
      <p class="lede">Cifras calculadas por el núcleo a partir de las licitaciones, sus etapas y las decisiones de los revisores. Datos sintéticos.</p>
    </section>
    @if (error()) { <p class="error glass" role="alert">{{ error() }}</p> }
    @if (d(); as d) {
      <div class="kpis">
        <div class="kpi glass"><span class="k">Licitaciones abiertas</span><span class="v" data-testid="open">{{ d.open }}</span></div>
        <div class="kpi glass"><span class="k">Importe en cartera</span><span class="v" data-testid="open-budget">{{ money(d.openBudget) }}</span>
          <span class="s">presupuestos calculados, sin IVA</span></div>
        <div class="kpi glass"><span class="k">Tasa de éxito</span><span class="v ok" data-testid="win-rate">{{ d.winRatePct === null ? '—' : d.winRatePct + ' %' }}</span>
          <span class="s">adjudicadas / resueltas</span></div>
        <div class="kpi glass"><span class="k">Adjudicado</span><span class="v">{{ money(d.awardedBudget) }}</span></div>
        <div class="kpi glass"><span class="k">Por revisar</span><span class="v" [class.alert]="pending() > 0">{{ pending() }}</span>
          <span class="s">la más antigua hace {{ hours(d.oldestPendingHours) }}</span></div>
        <div class="kpi glass"><span class="k">Ejecución automática</span><span class="v">{{ autoPct() }} %</span>
          <span class="s">{{ d.executedAutomatically }} sin persona · {{ d.executedByPerson }} decididas</span></div>
      </div>

      <div class="grid">
        <section class="panel glass reveal" aria-labelledby="d-funnel">
          <h2 id="d-funnel">Embudo por etapa</h2>
          <div class="bars" data-testid="funnel">
            @for (b of funnel(); track b.label) {
              <div class="b-row"><span class="b-label">{{ b.label }}</span>
                <span class="b-lane"><span class="b-bar" [class]="'b-bar ' + (b.tone ?? '')" [style.width.%]="b.pct"></span></span>
                <span class="b-val">{{ b.text }}</span></div>
            }
          </div>
        </section>

        <section class="panel glass reveal" aria-labelledby="d-dead">
          <h2 id="d-dead">Vencimientos en 30 días</h2>
          <ul class="list" data-testid="deadlines">
            @for (x of d.upcomingDeadlines; track x.code) {
              <li [class.late]="x.daysLeft < 0" [class.soon]="x.daysLeft >= 0 && x.daysLeft <= 7">
                <b>{{ x.code }}</b> <span>{{ x.date }}</span>
                <span class="when">{{ x.daysLeft === 0 ? 'hoy' : x.daysLeft > 0 ? 'en ' + x.daysLeft + ' días' : 'vencida hace ' + (-x.daysLeft) + ' días' }}</span>
              </li>
            } @empty { <li class="muted">Nada vence en los próximos 30 días.</li> }
          </ul>
          <a routerLink="/cartera" class="more">Ir a la cartera →</a>
        </section>

        <section class="panel glass reveal" aria-labelledby="d-load">
          <h2 id="d-load">Carga de revisión por tipo</h2>
          <div class="bars">
            @for (b of load(); track b.label) {
              <div class="b-row"><span class="b-label">{{ b.label }}</span>
                <span class="b-lane"><span class="b-bar warn" [style.width.%]="b.pct"></span></span><span class="b-val">{{ b.text }}</span></div>
            } @empty { <p class="muted">Nada pendiente.</p> }
          </div>
        </section>

        <section class="panel glass reveal" aria-labelledby="d-time">
          <h2 id="d-time">Tiempo medio en cada etapa</h2>
          <div class="bars">
            @for (b of times(); track b.label) {
              <div class="b-row"><span class="b-label">{{ b.label }}</span>
                <span class="b-lane"><span class="b-bar violet" [style.width.%]="b.pct"></span></span><span class="b-val">{{ b.text }}</span></div>
            } @empty { <p class="muted">Aún no hay cambios de etapa.</p> }
          </div>
        </section>

        <section class="panel glass reveal wide" aria-labelledby="d-dec">
          <h2 id="d-dec">Cómo deciden los revisores</h2>
          <div class="decisions">
            @for (b of decisions(); track b.label) {
              <div class="dec glass"><span class="v" [class]="'v ' + (b.tone ?? '')">{{ b.value }}</span><span class="k">{{ b.label }}</span></div>
            }
            <div class="dec glass"><span class="v alert">{{ d.rulesFailed }}</span><span class="k">rechazadas por las reglas</span></div>
          </div>
        </section>
      </div>
    }
  `,
  styles: `
    .hero { padding: 88px 8px 20px; }
    h1 { font-size: clamp(2.2rem, 5.5vw, 4.4rem); line-height: 1.02; letter-spacing: -0.04em; margin: 12px 0 18px; }
    .lede { color: var(--muted); font-size: 1.1rem; max-width: 60ch; margin: 0; }
    .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 14px; margin: 24px 0; }
    .kpi { padding: 18px 22px; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .k { color: var(--muted); font-size: .85rem; }
    .v { font-size: clamp(1.2rem, 2.6vw, 1.9rem); font-weight: 800; letter-spacing: -0.03em; white-space: nowrap; }
    .v.ok { color: var(--ok); } .v.alert, .alert { color: var(--accent-3); }
    .s { color: var(--muted); font-size: .8rem; }
    .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; margin-bottom: 24px; }
    @media (max-width: 900px) { .grid { grid-template-columns: 1fr; } }
    .panel { padding: 24px; min-width: 0; }
    .wide { grid-column: 1 / -1; }
    h2 { margin: 0 0 16px; font-size: 1.15rem; }
    .bars { display: grid; gap: 10px; }
    .b-row { display: grid; grid-template-columns: 150px minmax(0, 1fr) 70px; gap: 10px; align-items: center; }
    @media (max-width: 520px) { .b-row { grid-template-columns: 100px minmax(0, 1fr) 64px; } }
    .b-label { font-size: .9rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .b-lane { height: 14px; background: var(--surface); border-radius: 999px; overflow: hidden; }
    .b-bar { display: block; height: 100%; border-radius: 999px; background: linear-gradient(90deg, var(--accent), #b6ff8f); min-width: 2px; }
    .b-bar.warn { background: linear-gradient(90deg, var(--warn), #ffd27a); }
    .b-bar.violet { background: linear-gradient(90deg, var(--accent-2), #a993ff); }
    .b-bar.ok { background: var(--ok); } .b-bar.bad { background: var(--accent-3); } .b-bar.muted { background: rgba(255,255,255,.25); }
    .b-val { text-align: right; font-weight: 700; font-size: .9rem; white-space: nowrap; }
    .list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
    .list li { display: flex; gap: 10px; align-items: baseline; }
    .list .when { margin-left: auto; color: var(--muted); font-size: .88rem; }
    .list li.soon .when { color: var(--warn); } .list li.late .when { color: var(--accent-3); }
    .more { display: inline-block; margin-top: 14px; color: var(--accent); font-weight: 700; text-decoration: none; }
    .decisions { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
    .dec { padding: 16px 20px; display: flex; flex-direction: column; }
    .v.violet { color: var(--accent-2); }
    .muted { color: var(--muted); }
    .error { padding: 20px 24px; margin: 24px 8px; border-color: rgba(255,61,113,.6); }
  `,
})
export class Dashboard {
  private readonly api = inject(Api);
  readonly d = signal<DashboardData | null>(null);
  readonly error = signal<string | null>(null);

  readonly pending = computed(() => Object.values(this.d()?.pendingByType ?? {}).reduce((a, b) => a + b, 0));
  readonly autoPct = computed(() => {
    const d = this.d();
    const total = (d?.executedAutomatically ?? 0) + (d?.executedByPerson ?? 0);
    return total ? Math.round((100 * d!.executedAutomatically) / total) : 0;
  });
  readonly funnel = computed<Bar[]>(() => {
    const d = this.d();
    if (!d) return [];
    return bars(ORDER.map((s) => [STAGE_LABELS[s], d.tendersByStage[s] ?? 0] as [string, number]))
      .map((b, i) => ({ ...b, tone: ORDER[i] === 'AWARDED' ? 'ok' : ORDER[i] === 'LOST' ? 'bad' : ORDER[i] === 'DISCARDED' ? 'muted' : '' }));
  });
  readonly load = computed<Bar[]>(() => bars(Object.entries(this.d()?.pendingByType ?? {})
    .sort((a, b) => b[1] - a[1]).map(([t, n]) => [TYPE_LABELS[t] ?? t, n] as [string, number])));
  readonly times = computed<Bar[]>(() => {
    const m = this.d()?.meanMinutesInStage ?? {};
    return bars(ORDER.filter((s) => m[s] !== undefined).map((s) => [STAGE_LABELS[s], Number(m[s]) / 60] as [string, number]), hoursText);
  });
  readonly decisions = computed<Bar[]>(() => {
    const o = this.d()?.decisionsByOutcome ?? {};
    return [
      { label: 'aprobadas', value: o['APPROVE'] ?? 0, pct: 0, text: '', tone: 'ok' },
      { label: 'corregidas', value: o['CORRECT'] ?? 0, pct: 0, text: '', tone: 'violet' },
      { label: 'rechazadas por una persona', value: o['REJECT'] ?? 0, pct: 0, text: '', tone: 'alert' },
    ];
  });

  constructor() {
    this.api.dashboard().subscribe({
      next: (d) => this.d.set(d),
      error: (e) => this.error.set(`No se pudo cargar el panel: ${e?.status ? 'HTTP ' + e.status : 'sin conexión'}`),
    });
  }

  money(v: number | null): string { return euros(v); }
  hours(h: number | null): string { return hoursText(h); }
}
