import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, CurvePoint, SiteView } from '../api';

const W = 640;
const H = 280;
const PAD = { l: 44, r: 16, t: 16, b: 34 };

/** Points of a progress line in the chart's coordinates (x by week, y by percent). */
export function linePoints(curve: CurvePoint[], key: 'plannedPct' | 'actualPct', maxWeek: number): string {
  const x = (w: number) => PAD.l + ((w - 1) / Math.max(1, maxWeek - 1)) * (W - PAD.l - PAD.r);
  const y = (p: number) => PAD.t + (1 - Number(p) / 100) * (H - PAD.t - PAD.b);
  return curve.map((c) => `${x(c.week).toFixed(1)},${y(c[key]).toFixed(1)}`).join(' ');
}

/** Site progress: the progress curve, the late activities and what they do to the end date. */
@Component({
  selector: 'app-site',
  imports: [RouterLink],
  template: `
    @if (site(); as s) {
      <a [routerLink]="['/licitaciones', s.tenderId]" class="back">← Ficha de {{ s.tenderCode }}</a>
      <header class="head reveal">
        <p class="eyebrow">Seguimiento de obra · semana {{ s.statusWeek }} · corte {{ s.cutDate }}</p>
        <h1>{{ s.tenderCode }}:
          @if (s.analysis.impactDays > 0) { <span class="late" data-testid="impact">{{ s.analysis.impactIsLowerBound ? '≥ ' : '' }}{{ s.analysis.impactDays }} días de retraso</span> en el fin de obra }
          @else { <span class="ok" data-testid="impact">en plazo</span> }
        </h1>
        @if (!s.progressApproved || !s.scheduleApproved) {
          <p class="banner" role="note">Datos {{ !s.progressApproved ? 'de avance' : '' }}{{ !s.progressApproved && !s.scheduleApproved ? ' y ' : '' }}{{ !s.scheduleApproved ? 'de planning' : '' }} pendientes de aprobación: el cálculo es del núcleo, pero una persona aún no los ha validado.</p>
        }
        <div class="kpis">
          <div class="kpi glass"><span class="k">Avance real</span><span class="v ok" data-testid="actual">{{ last()?.actualPct ?? 0 }} %</span>
            <span class="s">planificado {{ last()?.plannedPct ?? 0 }} %</span></div>
          <div class="kpi glass"><span class="k">Índice de cumplimiento</span><span class="v" [class.late]="spi() !== null && spi()! < 1">{{ spi() ?? '—' }}</span>
            <span class="s">real / planificado</span></div>
          <div class="kpi glass"><span class="k">Fin previsto</span><span class="v" [class.late]="s.analysis.impactDays > 0">{{ s.forecastEnd }}</span>
            <span class="s">planificado {{ s.plannedEnd }}{{ s.analysis.impactIsLowerBound ? ' · como pronto' : '' }}</span></div>
          <div class="kpi glass"><span class="k">Actividades en alarma</span><span class="v" [class.late]="alarms() > 0" data-testid="alarms">{{ alarms() }}</span>
            <span class="s">retraso mayor que su holgura</span></div>
        </div>
        @if (s.contractDeadline) {
          <p class="muted small">Plazo contractual hasta {{ s.contractDeadline }}{{ s.forecastEnd > s.contractDeadline ? ': el fin previsto lo supera.' : '.' }}</p>
        }
      </header>

      <section class="panel glass reveal" aria-labelledby="curve-title">
        <h2 id="curve-title">Curva de avance</h2>
        <svg [attr.viewBox]="'0 0 ' + w + ' ' + h" class="chart" role="img"
             [attr.aria-label]="'Avance acumulado por semana: planificado frente a real, semana ' + s.statusWeek + ', real ' + (last()?.actualPct ?? 0) + ' % frente a ' + (last()?.plannedPct ?? 0) + ' % planificado'">
          @for (g of grid; track g) {
            <line [attr.x1]="pad.l" [attr.x2]="w - pad.r" [attr.y1]="gy(g)" [attr.y2]="gy(g)" class="grid" />
            <text [attr.x]="pad.l - 8" [attr.y]="gy(g) + 4" class="axis" text-anchor="end">{{ g }}%</text>
          }
          @for (c of s.analysis.curve; track c.week) {
            <text [attr.x]="gx(c.week)" [attr.y]="h - 10" class="axis" text-anchor="middle">S{{ c.week }}</text>
          }
          <polyline [attr.points]="actual()" class="actual" data-testid="actual-line" />
          <polyline [attr.points]="planned()" class="planned" />
          @if (last(); as l) {
            <circle [attr.cx]="gx(l.week)" [attr.cy]="gy(l.actualPct)" r="5" class="dot" />
          }
        </svg>
        <p class="legend"><span class="sw planned-sw"></span> Planificado <span class="sw actual-sw"></span> Real</p>
      </section>

      <section class="panel glass reveal" aria-labelledby="dev-title">
        <h2 id="dev-title">Actividades a la fecha de corte</h2>
        <div class="rows">
          @for (d of s.analysis.deviations; track d.activity) {
            <div class="row" [class.alarm]="d.alarm" [attr.data-testid]="'dev-' + d.activity">
              <span class="name"><b>{{ d.activity }}</b> {{ d.name }} @if (d.critical) { <span class="pill crit">crítica</span> }</span>
              <span class="bars" [attr.aria-label]="'planificado ' + d.plannedPct + ' %, real ' + d.actualPct + ' %'">
                <span class="bar planned-bar" [style.width.%]="d.plannedPct"></span>
                <span class="bar actual-bar" [style.width.%]="d.actualPct"></span>
              </span>
              <span class="pct">{{ d.actualPct }} / {{ d.plannedPct }} %</span>
              <span class="delay">
                @if (d.delayDays > 0) { {{ d.delayIsLowerBound ? '≥ ' : '' }}{{ d.delayDays }} d · holgura {{ d.totalFloat }} d }
                @else { al día }
              </span>
              @if (d.alarm) { <span class="pill fail state">Alarma</span> } @else { <span class="pill ok state">OK</span> }
            </div>
          }
        </div>
        <p class="muted small">Retraso = días laborables transcurridos desde el inicio previsto menos el trabajo hecho; si la actividad no ha empezado es un mínimo (≥). Alarma cuando supera la holgura total. El impacto en el fin se calcula con la ruta crítica del núcleo.</p>
      </section>
    } @else if (error()) {
      <a routerLink="/cartera" class="back">← Cartera</a>
      <p class="error glass" role="alert">{{ error() }}</p>
    }
  `,
  styles: `
    .back { display: inline-block; margin: 32px 8px 0; color: var(--muted); text-decoration: none; }
    .head { padding: 24px 8px 8px; }
    h1 { font-size: clamp(1.9rem, 4.2vw, 3.3rem); letter-spacing: -0.035em; line-height: 1.08; margin: 10px 0 16px; }
    .late { color: var(--accent-3); }
    .ok:not(.pill) { color: var(--ok); }
    .state { justify-self: end; }
    .banner { padding: 12px 16px; border-radius: 14px; background: rgba(255,176,32,.12); border: 1px solid rgba(255,176,32,.5); }
    .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 14px; margin-top: 20px; }
    .kpi { padding: 16px 20px; display: flex; flex-direction: column; gap: 2px; }
    .k { color: var(--muted); font-size: .85rem; }
    .v { font-size: 1.7rem; font-weight: 800; letter-spacing: -0.02em; }
    .s { color: var(--muted); font-size: .82rem; }
    .panel { padding: 24px; margin: 18px 0; }
    h2 { margin: 0 0 14px; font-size: 1.15rem; }
    .chart { width: 100%; height: auto; display: block; }
    .grid { stroke: rgba(255,255,255,.08); }
    .axis { fill: var(--muted); font-size: 11px; }
    .planned { fill: none; stroke: var(--accent-2); stroke-width: 3; stroke-dasharray: 7 6; }
    .actual { fill: none; stroke: var(--accent); stroke-width: 3.5; filter: drop-shadow(0 0 6px rgba(83,252,24,.6)); }
    .dot { fill: var(--accent); }
    .legend { display: flex; align-items: center; gap: 8px; color: var(--muted); font-size: .9rem; margin: 10px 0 0; }
    .sw { display: inline-block; width: 22px; height: 4px; border-radius: 2px; margin-left: 10px; }
    .planned-sw { background: var(--accent-2); } .actual-sw { background: var(--accent); }
    .rows { display: grid; gap: 10px; }
    .row { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(0, 1.4fr) 90px 150px 74px; gap: 12px; align-items: center;
      padding: 10px 12px; border-radius: 12px; border: 1px solid transparent; }
    .row.alarm { border-color: rgba(255,61,113,.55); background: rgba(255,61,113,.06); }
    @media (max-width: 760px) { .row { grid-template-columns: 1fr auto; } .bars { grid-column: 1 / -1; } .pct, .delay { font-size: .85rem; }
      .state { grid-column: 2; grid-row: 1; } }
    .name { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .pill.crit { font-size: .68rem; padding: 2px 8px; }
    .bars { position: relative; height: 18px; background: var(--surface); border-radius: 999px; overflow: hidden; }
    .bar { position: absolute; left: 0; border-radius: 999px; }
    .planned-bar { top: 0; height: 100%; background: rgba(124,92,255,.35); }
    .actual-bar { top: 4px; height: 10px; background: linear-gradient(90deg, var(--accent), #b6ff8f); }
    .pct, .delay { color: var(--muted); font-size: .9rem; }
    .muted { color: var(--muted); }
    .small { font-size: .85rem; margin-top: 14px; }
    .error { padding: 20px 24px; margin: 24px 8px; border-color: rgba(255,61,113,.6); }
  `,
})
export class Site {
  private readonly api = inject(Api);
  readonly id = input.required<string>();
  readonly site = signal<SiteView | null>(null);
  readonly error = signal<string | null>(null);
  readonly w = W;
  readonly h = H;
  readonly pad = PAD;
  readonly grid = [0, 25, 50, 75, 100];

  readonly maxWeek = computed(() => Math.max(1, ...(this.site()?.analysis.curve.map((c) => c.week) ?? [1])));
  readonly last = computed(() => this.site()?.analysis.curve.at(-1) ?? null);
  readonly planned = computed(() => linePoints(this.site()?.analysis.curve ?? [], 'plannedPct', this.maxWeek()));
  readonly actual = computed(() => linePoints(this.site()?.analysis.curve ?? [], 'actualPct', this.maxWeek()));
  readonly alarms = computed(() => this.site()?.analysis.deviations.filter((d) => d.alarm).length ?? 0);
  readonly spi = computed(() => {
    const l = this.last();
    return l && Number(l.plannedPct) > 0 ? Math.round((Number(l.actualPct) / Number(l.plannedPct)) * 100) / 100 : null;
  });

  constructor() {
    effect(() => {
      this.api.site(Number(this.id())).subscribe({
        next: (s) => this.site.set(s),
        error: (e) => this.error.set(e?.status === 404 ? 'Esta licitación aún no tiene planning y avance de obra.' : 'No se pudo cargar el seguimiento.'),
      });
    });
  }

  gx(week: number): number {
    return PAD.l + ((week - 1) / Math.max(1, this.maxWeek() - 1)) * (W - PAD.l - PAD.r);
  }

  gy(pct: number): number {
    return PAD.t + (1 - Number(pct) / 100) * (H - PAD.t - PAD.b);
  }
}
