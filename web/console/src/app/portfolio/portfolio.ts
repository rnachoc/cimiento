import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api, NOTE_REQUIRED, PortfolioItem, Stage, STAGE_LABELS } from '../api';

interface Column { stage: Stage; label: string; items: PortfolioItem[]; }

const OPEN: Stage[] = ['TO_REVIEW', 'UNDER_REVIEW', 'DECISION_PENDING', 'IN_PREPARATION', 'SUBMITTED'];
const CLOSED: Stage[] = ['AWARDED', 'LOST', 'DISCARDED'];
const DAY = 86_400_000;

/** Whole days from today to a YYYY-MM-DD date (negative when it has passed). */
export function daysLeft(date: string, today: Date = new Date()): number {
  const [y, m, d] = date.split('-').map(Number);
  const start = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate());
  return Math.round((Date.UTC(y, m - 1, d) - start) / DAY);
}

/** Bid pipeline: every tender by stage, with its deadline, recommendation and what is pending. */
@Component({
  selector: 'app-portfolio',
  imports: [RouterLink, FormsModule],
  template: `
    <section class="hero reveal" aria-labelledby="pf-title">
      <p class="eyebrow">Cartera de licitaciones</p>
      <h1 id="pf-title"><span class="count" data-testid="open-count">{{ open().length }}</span> licitaciones abiertas</h1>
      <p class="lede">Cada cambio de etapa lo valida el núcleo: decidir ofertar exige el análisis del pliego aprobado y un motivo; presentar exige que no quede nada por revisar.</p>
      <div class="stats">
        <div class="stat glass"><span class="n alert" data-testid="due-soon">{{ dueSoon() }}</span><span>vencen en 7 días o menos</span></div>
        <div class="stat glass"><span class="n">{{ waiting() }}</span><span>propuestas por revisar</span></div>
        <div class="stat glass"><span class="n">{{ count('SUBMITTED') }}</span><span>presentadas</span></div>
        <div class="stat glass"><span class="n ok">{{ count('AWARDED') }}</span><span>adjudicadas</span></div>
      </div>
    </section>

    @if (error()) { <p class="error glass" role="alert">{{ error() }}</p> }
    @if (loading()) { <p class="loading" aria-live="polite">Cargando cartera…</p> }

    <div class="board" role="list" aria-label="Etapas">
      @for (col of columns(); track col.stage) {
        <section class="col" role="listitem" [attr.aria-labelledby]="'col-' + col.stage" [attr.data-testid]="'col-' + col.stage">
          <div class="col-head">
            <h2 [id]="'col-' + col.stage">{{ col.label }}</h2>
            <span class="pill">{{ col.items.length }}</span>
          </div>
          @for (t of col.items; track t.tenderId) {
            <article class="card glass" [attr.data-testid]="'tender-' + t.code" [class.late]="isLate(t)">
              <div class="card-top">
                <span class="tender">{{ t.code }}</span>
                @if (t.recommendation) {
                  <span class="pill" [class.ok]="t.recommendation === 'BID'" [class.fail]="t.recommendation === 'NO_BID'"
                        [class.warn]="t.recommendation === 'REVIEW'">{{ recLabel(t.recommendation) }}</span>
                }
              </div>
              <p class="title">{{ t.title }}</p>
              @if (t.bidDeadline) {
                <p class="deadline" [class.soon]="isSoon(t)" [class.late]="isLate(t)">
                  Presentación {{ t.bidDeadline }} · {{ deadlineText(t.bidDeadline) }}
                  @if (!t.deadlineApproved) { <span class="muted">(sin aprobar)</span> }
                </p>
              } @else {
                <p class="deadline muted">Sin fecha de presentación</p>
              }
              @if (t.pendingReviews > 0) {
                <a routerLink="/" class="pending">{{ t.pendingReviews }} por revisar →</a>
              }
              @if (t.next.length) {
                @if (editing() === t.tenderId) {
                  <form class="move" (submit)="$event.preventDefault(); confirm(t)">
                    <label [for]="'note-' + t.tenderId">Motivo ({{ label(target()!) }})</label>
                    <textarea [id]="'note-' + t.tenderId" name="note" rows="2" [(ngModel)]="note"
                              placeholder="Por qué se toma esta decisión" required></textarea>
                    <div class="row">
                      <button class="btn btn-approve" type="submit" [disabled]="busy()">Confirmar</button>
                      <button class="btn btn-ghost" type="button" (click)="cancel()">Cancelar</button>
                    </div>
                  </form>
                } @else {
                  <div class="row">
                    @for (s of t.next; track s) {
                      <button class="btn small" type="button" [class.btn-reject]="s === 'DISCARDED' || s === 'LOST'"
                              [class.btn-ghost]="s !== 'DISCARDED' && s !== 'LOST'" [disabled]="busy()"
                              [attr.data-testid]="'move-' + t.code + '-' + s" (click)="start(t, s)">{{ label(s) }}</button>
                    }
                  </div>
                }
              }
              @if (moveError() && lastTender() === t.tenderId) {
                <ul class="problems" role="alert">
                  @for (m of moveError()!; track $index) { <li>{{ m }}</li> }
                </ul>
              }
            </article>
          } @empty {
            <p class="none">Ninguna</p>
          }
        </section>
      }
    </div>
  `,
  styles: `
    .hero { padding: 88px 8px 28px; }
    h1 { font-size: clamp(2.2rem, 5.5vw, 4.4rem); line-height: 1.02; letter-spacing: -0.04em; margin: 12px 0 18px; max-width: 16ch; }
    .count { background: linear-gradient(120deg, var(--accent), #b6ff8f 40%, var(--accent-2)); -webkit-background-clip: text; background-clip: text; color: transparent; }
    .lede { color: var(--muted); font-size: 1.1rem; max-width: 60ch; margin: 0 0 30px; }
    .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 14px; }
    .stat { padding: 18px 22px; display: flex; flex-direction: column; gap: 2px; color: var(--muted); }
    .n { font-size: 2.2rem; font-weight: 800; color: var(--text); letter-spacing: -0.03em; }
    .n.alert { color: var(--accent-3); }
    .n.ok { color: var(--ok); }
    .board { display: grid; grid-auto-flow: column; grid-auto-columns: minmax(270px, 1fr); gap: 16px; overflow-x: auto;
      padding: 28px 8px 32px; scroll-snap-type: x mandatory; scrollbar-width: thin; }
    .col { scroll-snap-align: start; display: flex; flex-direction: column; gap: 12px; min-width: 0; }
    .col-head { display: flex; align-items: center; gap: 10px; padding: 0 4px; }
    h2 { font-size: 1.05rem; margin: 0; letter-spacing: -0.01em; }
    .card { padding: 16px 18px; display: flex; flex-direction: column; gap: 10px; transition: border-color .3s var(--ease), transform .3s var(--ease); }
    .card:hover { border-color: rgba(83,252,24,.45); transform: translateY(-3px); }
    .card.late { border-color: rgba(255,61,113,.6); }
    .card-top { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .tender { font-weight: 800; }
    .title { margin: 0; font-weight: 600; line-height: 1.3; }
    .deadline { margin: 0; font-size: .9rem; color: var(--muted); }
    .deadline.soon { color: var(--warn); }
    .deadline.late { color: var(--accent-3); }
    .muted { color: var(--muted); }
    .pending { color: var(--accent); font-weight: 700; font-size: .9rem; text-decoration: none; }
    .row { display: flex; flex-wrap: wrap; gap: 8px; }
    .btn.small { padding: 8px 14px; font-size: .85rem; }
    .move { display: flex; flex-direction: column; gap: 8px; }
    .move label { font-size: .85rem; color: var(--muted); }
    textarea { width: 100%; border-radius: 12px; border: 1px solid var(--border); background: var(--bg-2); color: var(--text); padding: 10px 12px; font: inherit; resize: vertical; }
    .problems { margin: 0; padding: 10px 14px 10px 28px; border-radius: 12px; background: rgba(255,61,113,.12); color: #ffd0dc; font-size: .88rem; }
    .none { color: var(--muted); font-size: .9rem; padding: 0 4px; }
    .error, .loading { padding: 20px 24px; margin: 24px 8px; }
    .error { border-color: rgba(255,61,113,.6); }
  `,
})
export class Portfolio {
  private readonly api = inject(Api);
  readonly items = signal<PortfolioItem[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly editing = signal<number | null>(null);
  readonly target = signal<Stage | null>(null);
  readonly moveError = signal<string[] | null>(null);
  readonly lastTender = signal<number | null>(null);
  note = '';

  readonly open = computed(() => this.items().filter((t) => OPEN.includes(t.stage)));
  readonly dueSoon = computed(() => this.open().filter((t) => this.isSoon(t) || this.isLate(t)).length);
  readonly waiting = computed(() => this.items().reduce((n, t) => n + t.pendingReviews, 0));
  readonly columns = computed<Column[]>(() => [...OPEN, ...CLOSED].map((stage) => ({
    stage, label: STAGE_LABELS[stage],
    items: this.items().filter((t) => t.stage === stage)
      .sort((a, b) => (a.bidDeadline ?? '9999').localeCompare(b.bidDeadline ?? '9999') || a.code.localeCompare(b.code)),
  })));

  constructor() {
    this.load();
  }

  load(): void {
    this.api.portfolio().subscribe({
      next: (rows) => { this.items.set(rows); this.loading.set(false); },
      error: (e) => { this.error.set(`No se pudo cargar la cartera: ${e?.status ? 'HTTP ' + e.status : 'sin conexión'}`); this.loading.set(false); },
    });
  }

  count(stage: Stage): number {
    return this.items().filter((t) => t.stage === stage).length;
  }

  label(s: Stage): string {
    return STAGE_LABELS[s];
  }

  recLabel(r: string): string {
    return ({ BID: 'Ofertar', NO_BID: 'No ofertar', REVIEW: 'Revisar' } as Record<string, string>)[r] ?? r;
  }

  private isOpen(t: PortfolioItem): boolean {
    return OPEN.includes(t.stage) && t.stage !== 'SUBMITTED';
  }

  isSoon(t: PortfolioItem): boolean {
    if (!t.bidDeadline || !this.isOpen(t)) return false;
    const d = daysLeft(t.bidDeadline);
    return d >= 0 && d <= 7;
  }

  isLate(t: PortfolioItem): boolean {
    return !!t.bidDeadline && this.isOpen(t) && daysLeft(t.bidDeadline) < 0;
  }

  deadlineText(date: string): string {
    const d = daysLeft(date);
    if (d === 0) return 'hoy';
    if (d === 1) return 'mañana';
    return d > 0 ? `en ${d} días` : `hace ${-d} días`;
  }

  start(t: PortfolioItem, s: Stage): void {
    this.moveError.set(null);
    if (NOTE_REQUIRED.includes(s)) {
      this.editing.set(t.tenderId);
      this.target.set(s);
      this.note = '';
      return;
    }
    this.send(t, s, '');
  }

  confirm(t: PortfolioItem): void {
    const s = this.target();
    if (!s) return;
    if (!this.note.trim()) {
      this.lastTender.set(t.tenderId);
      this.moveError.set(['Escribe el motivo de la decisión.']);
      return;
    }
    this.send(t, s, this.note);
  }

  cancel(): void {
    this.editing.set(null);
    this.target.set(null);
    this.moveError.set(null);
  }

  private send(t: PortfolioItem, s: Stage, note: string): void {
    this.busy.set(true);
    this.lastTender.set(t.tenderId);
    this.api.moveStage(t.tenderId, s, note).subscribe({
      next: (updated) => {
        this.items.update((rows) => rows.map((r) => (r.tenderId === updated.tenderId ? updated : r)));
        this.busy.set(false);
        this.cancel();
      },
      error: (e) => {
        this.busy.set(false);
        const details = e?.error?.details;
        this.moveError.set(Array.isArray(details) && details.length ? details : [e?.error?.message ?? `HTTP ${e?.status ?? '?'}`]);
      },
    });
  }
}
