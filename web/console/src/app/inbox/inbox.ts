import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, Proposal, TYPE_LABELS } from '../api';

interface Rail { type: string; label: string; items: Proposal[]; }

/** Inbox: what the rules sent to a person, grouped in rails by proposal type. */
@Component({
  selector: 'app-inbox',
  imports: [RouterLink],
  template: `
    <section class="hero reveal" aria-labelledby="hero-title">
      <p class="eyebrow">Revisión humana</p>
      <h1 id="hero-title">
        <span class="count" data-testid="pending-count">{{ pending().length }}</span>
        propuestas esperan tu decisión
      </h1>
      <p class="lede">Las reglas del núcleo ya han comprobado cálculos, unidades, plazos y citas. Aquí decides tú.</p>
      <div class="stats">
        <div class="stat glass"><span class="n">{{ pending().length }}</span><span>pendientes</span></div>
        <div class="stat glass"><span class="n alert">{{ flagged() }}</span><span>con instrucción plantada</span></div>
        <div class="stat glass"><span class="n">{{ executed() }}</span><span>ejecutadas</span></div>
        <div class="stat glass"><span class="n">{{ failed() }}</span><span>rechazadas por reglas</span></div>
      </div>
    </section>

    @if (error()) {
      <p class="error glass" role="alert">No se pudo cargar la bandeja: {{ error() }}</p>
    }
    @if (loading()) {
      <p class="loading" aria-live="polite">Cargando propuestas…</p>
    }

    @for (rail of rails(); track rail.type) {
      <section class="rail reveal" [attr.aria-labelledby]="'rail-' + rail.type">
        <div class="rail-head">
          <h2 [id]="'rail-' + rail.type">{{ rail.label }}</h2>
          <span class="pill">{{ rail.items.length }}</span>
        </div>
        <ul class="track" role="list">
          @for (p of rail.items; track p.id) {
            <li>
              <a class="card glass" [routerLink]="['/proposals', p.id]" [class.danger]="hasInjection(p)"
                 [attr.data-testid]="'card-' + p.id" [attr.aria-label]="cardLabel(p)">
                <span class="card-glow" aria-hidden="true"></span>
                <span class="card-top">
                  <span class="tender">{{ p.tenderCode }}</span>
                  <span class="id">#{{ p.id }}</span>
                </span>
                <span class="title">{{ headline(p) }}</span>
                <span class="chips">
                  @for (r of p.routingReasons.slice(0, 3); track r) {
                    <span class="pill" [class.fail]="r === 'INJECTION_DETECTED'">{{ reasonLabel(r) }}</span>
                  }
                </span>
                <span class="cta">Revisar →</span>
              </a>
            </li>
          }
        </ul>
      </section>
    } @empty {
      @if (!loading() && !error()) {
        <p class="empty glass">Nada pendiente. Las reglas no han enviado ninguna propuesta a revisión.</p>
      }
    }
  `,
  styles: `
    .hero { padding: 88px 8px 40px; }
    h1 { font-size: clamp(2.4rem, 6vw, 5rem); line-height: 1.02; letter-spacing: -0.04em; margin: 12px 0 18px; max-width: 14ch; }
    .count { background: linear-gradient(120deg, var(--accent), #b6ff8f 40%, var(--accent-2)); -webkit-background-clip: text; background-clip: text; color: transparent; }
    .lede { color: var(--muted); font-size: 1.2rem; max-width: 52ch; margin: 0 0 36px; }
    .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 14px; }
    .stat { padding: 18px 22px; display: flex; flex-direction: column; gap: 2px; color: var(--muted); }
    .n { font-size: 2.2rem; font-weight: 800; color: var(--text); letter-spacing: -0.03em; }
    .n.alert { color: var(--accent-3); }
    .rail { margin: 36px 0 8px; }
    .rail-head { display: flex; align-items: center; gap: 12px; padding: 0 8px; }
    h2 { font-size: 1.4rem; margin: 0; letter-spacing: -0.02em; }
    .track { list-style: none; margin: 0; padding: 18px 8px 26px; display: grid; grid-auto-flow: column;
      grid-auto-columns: minmax(280px, 320px); gap: 18px; overflow-x: auto; scroll-snap-type: x mandatory;
      scrollbar-width: thin; }
    .track > li { scroll-snap-align: start; }
    .card { position: relative; overflow: hidden; display: flex; flex-direction: column; gap: 14px; min-height: 210px;
      padding: 22px; text-decoration: none; transition: transform .35s var(--ease), border-color .35s var(--ease), box-shadow .35s var(--ease); }
    .card:hover, .card:focus-visible { transform: translateY(-6px) scale(1.03); border-color: rgba(83,252,24,.55); box-shadow: var(--shadow); }
    .card.danger { border-color: rgba(255,61,113,.6); }
    .card-glow { position: absolute; inset: -40% -40% auto auto; width: 220px; height: 220px; border-radius: 50%;
      background: radial-gradient(circle, rgba(124,92,255,.35), transparent 70%); transition: transform .5s var(--ease); }
    .card:hover .card-glow { transform: translate(-30px, 30px) scale(1.2); }
    .card.danger .card-glow { background: radial-gradient(circle, rgba(255,61,113,.4), transparent 70%); }
    .card-top { display: flex; justify-content: space-between; color: var(--muted); font-size: .85rem; position: relative; }
    .tender { font-weight: 700; color: var(--text); }
    .title { font-size: 1.15rem; font-weight: 700; line-height: 1.3; position: relative; }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; position: relative; }
    .cta { margin-top: auto; color: var(--accent); font-weight: 700; position: relative; }
    .error, .empty, .loading { padding: 20px 24px; margin: 24px 8px; }
    .error { border-color: rgba(255,61,113,.6); }
  `,
})
export class Inbox {
  private readonly api = inject(Api);
  readonly all = signal<Proposal[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  readonly pending = computed(() => this.all().filter((p) => p.status === 'PENDING_REVIEW'));
  readonly executed = computed(() => this.all().filter((p) => p.status === 'EXECUTED').length);
  readonly failed = computed(() => this.all().filter((p) => p.status === 'RULES_FAILED').length);
  readonly flagged = computed(() => this.pending().filter((p) => this.hasInjection(p)).length);
  readonly rails = computed<Rail[]>(() => {
    const byType = new Map<string, Proposal[]>();
    for (const p of this.pending()) {
      byType.set(p.type, [...(byType.get(p.type) ?? []), p]);
    }
    return [...byType.entries()]
      .map(([type, items]) => ({ type, label: TYPE_LABELS[type] ?? type, items }))
      .sort((a, b) => b.items.length - a.items.length);
  });

  constructor() {
    this.api.proposals().subscribe({
      next: (rows) => { this.all.set(rows); this.loading.set(false); },
      error: (e) => { this.error.set(e?.status ? `HTTP ${e.status}` : 'sin conexión'); this.loading.set(false); },
    });
  }

  hasInjection(p: Proposal): boolean {
    return p.signals.includes('INJECTION_DETECTED') || p.routingReasons.includes('INJECTION_DETECTED');
  }

  headline(p: Proposal): string {
    const pl = p.payload ?? {};
    switch (p.type) {
      case 'BUDGET': return `Presupuesto de ${pl.lines?.length ?? 0} partidas`;
      case 'TAKEOFF': return `${pl.items?.length ?? 0} partidas medidas`;
      case 'SCHEDULE': return `Planning de ${pl.activities?.length ?? 0} actividades`;
      case 'RISK_SIGNAL': return pl.description ?? 'Señal de riesgo';
      case 'REPORT': return pl.title ?? (pl.recommendation ? `Recomendación: ${pl.recommendation}` : 'Informe');
      case 'BID_ANALYSIS': return `${Object.keys(pl.fields ?? {}).length} datos del pliego con cita`;
      case 'DOCUMENT_DRAFT': return 'Borrador de memoria técnica';
      case 'PROGRESS_UPDATE': return `Avance semana ${pl.week}`;
      default: return TYPE_LABELS[p.type] ?? p.type;
    }
  }

  reasonLabel(r: string): string {
    const labels: Record<string, string> = {
      TYPE_REQUIRES_REVIEW: 'Requiere persona', INJECTION_DETECTED: 'Instrucción plantada',
      AMOUNT_OVER_THRESHOLD: 'Importe alto', HIGH_RISK: 'Riesgo alto', CONTRACT_TERM: 'Plazo',
      PROGRESS_BEHIND_PLAN: 'Retraso',
    };
    return labels[r] ?? r;
  }

  cardLabel(p: Proposal): string {
    return `${TYPE_LABELS[p.type] ?? p.type} ${p.tenderCode}, propuesta ${p.id}: ${this.headline(p)}`;
  }
}
