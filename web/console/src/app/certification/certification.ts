import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, CertIssued, CertLine, CertPreview } from '../api';
import { Session } from '../session';

interface Group { activity: string; lines: CertLine[]; amount: number; pct: number; toDate: number; previous: number; period: number; }

/** Lines of the certification grouped by activity: the percent is the activity's, the amounts add up. */
export function groupByActivity(lines: CertLine[]): Group[] {
  const groups = new Map<string, Group>();
  for (const l of lines) {
    const g = groups.get(l.activity) ?? { activity: l.activity, lines: [], amount: 0, pct: l.pct, toDate: 0, previous: 0, period: 0 };
    g.lines.push(l);
    g.amount = round(g.amount + l.amount);
    g.toDate = round(g.toDate + l.toDate);
    g.previous = round(g.previous + l.previous);
    g.period = round(g.period + l.period);
    groups.set(l.activity, g);
  }
  return [...groups.values()];
}

const round = (n: number) => Math.round(n * 100) / 100;

const MONEY = new Intl.NumberFormat('es-ES', {
  style: 'currency', currency: 'EUR', minimumFractionDigits: 2, maximumFractionDigits: 2,
  useGrouping: 'always' as unknown as boolean,
});

/** A certification shows every cent: 1.264,45 €. */
export const money = (v: number): string => MONEY.format(v);

/** Why this person cannot issue a certification ('' when allowed); the core decides anyway. */
export function issueBlock(role: string | undefined, preview: CertPreview | null): string {
  if (!role) return 'Inicia sesión para emitir.';
  if (role === 'ESTIMATING') return 'Estudios no emite certificaciones.';
  return preview?.result.errors[0] ?? '';
}

/** Monthly certification: what the executed work is worth, calculated by the core. */
@Component({
  selector: 'app-certifications',
  imports: [RouterLink],
  template: `
    @if (preview(); as p) {
      <a [routerLink]="['/obra', p.tenderId]" class="back">← Seguimiento de {{ p.tenderCode }}</a>
      <header class="head reveal">
        <p class="eyebrow">Certificación mensual · {{ p.tenderCode }}</p>
        <h1>{{ monthLabel(p.result.month) }}</h1>
        @if (!p.budgetApproved || !p.progressApproved) {
          <p class="banner" role="note">{{ !p.budgetApproved ? 'El presupuesto' : 'El avance' }}{{ !p.budgetApproved && !p.progressApproved ? ' y el avance' : '' }}
            aún no están aprobados por una persona: puedes ver el cálculo, pero no emitirlo.</p>
        }
        <div class="pick">
          <label for="month">Mes</label>
          <input id="month" type="month" [value]="p.result.month" (change)="pick($any($event.target).value)" data-testid="month" />
          @if (p.result.cutWeek) { <span class="muted">corte en la semana {{ p.result.cutWeek }} ({{ p.result.cutDate }})</span> }
        </div>
      </header>

      <section class="panel glass reveal" aria-labelledby="c-lines">
        <h2 id="c-lines">Relación valorada</h2>
        <div class="scroll">
          <table data-testid="cert-table">
            <thead><tr><th>Actividad</th><th class="n">Presupuesto</th><th class="n">% a origen</th><th class="n">A origen</th>
              <th class="n">Anterior</th><th class="n">Este periodo</th></tr></thead>
            <tbody>
              @for (g of groups(); track g.activity) {
                <tr class="act" [attr.data-testid]="'act-' + g.activity">
                  <td><b>{{ g.activity }}</b><span class="muted cnt">{{ g.lines.length }} partida{{ g.lines.length === 1 ? '' : 's' }}</span></td>
                  <td class="n">{{ eur(g.amount) }}</td><td class="n">{{ g.pct }} %</td><td class="n">{{ eur(g.toDate) }}</td>
                  <td class="n">{{ eur(g.previous) }}</td><td class="n strong">{{ eur(g.period) }}</td>
                </tr>
                @for (l of g.lines; track l.code) {
                  <tr class="line"><td>{{ l.code }} <span class="muted">{{ l.description }}</span></td>
                    <td class="n">{{ eur(l.amount) }}</td><td class="n"></td><td class="n">{{ eur(l.toDate) }}</td>
                    <td class="n">{{ eur(l.previous) }}</td><td class="n">{{ eur(l.period) }}</td></tr>
                }
              }
            </tbody>
          </table>
        </div>
        <dl class="totals" data-testid="totals">
          <dt>Presupuesto</dt><dd>{{ eur(p.result.budgetTotal) }}</dd>
          <dt>Ejecutado a origen</dt><dd>{{ eur(p.result.toDate) }}</dd>
          <dt>Certificado anterior</dt><dd>{{ eur(p.result.previous) }}</dd>
          <dt class="strong">Este periodo</dt><dd class="strong" data-testid="period">{{ eur(p.result.period) }}</dd>
          <dt>Retención</dt><dd>− {{ eur(p.result.retention) }}</dd>
          <dt>Líquido</dt><dd>{{ eur(p.result.net) }}</dd>
          <dt>IVA</dt><dd>{{ eur(p.result.vat) }}</dd>
          <dt class="strong">Total a facturar</dt><dd class="strong" data-testid="payable">{{ eur(p.result.payable) }}</dd>
        </dl>

        @if (p.result.errors.length) {
          <ul class="problems" role="status" data-testid="cert-errors">
            @for (e of p.result.errors; track $index) { <li>{{ e }}</li> }
          </ul>
        }
        <div class="issue">
          <button class="btn btn-approve" type="button" data-testid="issue" [disabled]="busy() || !!block()" (click)="issue()">Emitir certificación</button>
          @if (block()) { <span class="muted" data-testid="why-not">{{ block() }}</span> }
          <span class="note" aria-live="polite">{{ feedback() }}</span>
        </div>
        <p class="muted small">Todo el cálculo es del núcleo: importe de cada partida por el porcentaje real de su actividad en la última semana que cierra el mes, menos lo ya certificado. La retención y el IVA son parámetros ficticios.</p>
      </section>

      <section class="panel glass reveal" aria-labelledby="c-issued">
        <h2 id="c-issued">Certificaciones emitidas</h2>
        @if (issued().length) {
          <div class="scroll">
            <table data-testid="issued">
              <thead><tr><th>Nº</th><th>Mes</th><th class="n">Semana</th><th class="n">Este periodo</th><th class="n">Total a facturar</th><th>Emitida por</th><th><span class="sr-only">Documento</span></th></tr></thead>
              <tbody>
                @for (c of issued(); track c.id) {
                  <tr><td>{{ c.number }}</td><td>{{ c.month }}</td><td class="n">{{ c.cutWeek }}</td><td class="n">{{ eur(c.period) }}</td>
                    <td class="n">{{ eur(c.payable) }}</td><td>{{ c.issuedBy }}</td>
                    <td><a class="pdf" [href]="pdfUrl(p.tenderId, c.number)" download [attr.data-testid]="'pdf-' + c.number"
                           [attr.aria-label]="'Descargar en PDF la certificación ' + c.number">PDF ↓</a></td></tr>
                }
              </tbody>
            </table>
          </div>
        } @else { <p class="muted">Todavía no se ha emitido ninguna.</p> }
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
    .banner { padding: 12px 16px; border-radius: 14px; background: rgba(255,176,32,.12); border: 1px solid rgba(255,176,32,.5); }
    .pick { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 16px; }
    .pick input { padding: 8px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--surface); color: var(--text); font: inherit; color-scheme: dark; }
    .panel { padding: 24px; margin: 18px 0; }
    h2 { margin: 0 0 14px; font-size: 1.15rem; }
    .scroll { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; min-width: 640px; }
    th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--border); }
    th { color: var(--muted); font-weight: 600; font-size: .85rem; }
    .n { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
    tr.act td { background: rgba(255,255,255,.03); }
    tr.line td { color: var(--muted); font-size: .9rem; }
    .strong { font-weight: 800; }
    .cnt { margin-left: 8px; }
    .totals { display: grid; grid-template-columns: 1fr auto; gap: 6px 24px; max-width: 420px; margin: 20px 0 0 auto; }
    .totals dt { color: var(--muted); } .totals dd { margin: 0; text-align: right; font-variant-numeric: tabular-nums; }
    .totals dt.strong { color: var(--text); }
    .problems { margin: 18px 0 0; padding: 12px 16px 12px 32px; border-radius: 12px; background: rgba(255,61,113,.08); border: 1px solid rgba(255,61,113,.45); }
    .issue { display: flex; flex-wrap: wrap; align-items: center; gap: 14px; margin-top: 18px; }
    .muted { color: var(--muted); } .small { font-size: .85rem; margin-top: 14px; }
    .error { padding: 20px 24px; margin: 24px 8px; border-color: rgba(255,61,113,.6); }
    .pdf { display: inline-block; padding: 4px 12px; border-radius: 999px; border: 1px solid var(--border); color: var(--accent); font-weight: 700; text-decoration: none; white-space: nowrap; }
    .pdf:hover { border-color: var(--accent); }
    .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
    @media (max-width: 520px) { .panel { padding: 16px; } .totals { max-width: none; } }
  `,
})
export class Certifications {
  private readonly api = inject(Api);
  private readonly session = inject(Session);
  readonly id = input.required<string>();
  readonly preview = signal<CertPreview | null>(null);
  readonly issued = signal<CertIssued[]>([]);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly feedback = signal('');
  readonly eur = money;

  readonly groups = computed(() => groupByActivity(this.preview()?.result.lines ?? []));
  readonly block = computed(() => issueBlock(this.session.person()?.role, this.preview()));

  constructor() {
    effect(() => this.load(Number(this.id())));
  }

  private load(id: number, month?: string): void {
    this.api.certification(id, month).subscribe({
      next: (p) => this.preview.set(p),
      error: (e) => this.error.set(e?.status === 404
        ? 'Esta licitación aún no tiene presupuesto y avance de obra.' : 'No se pudo cargar la certificación.'),
    });
    this.api.certifications(id).subscribe({ next: (c) => this.issued.set(c) });
  }

  /** The issued snapshot as a PDF, drawn by the core. */
  pdfUrl(tenderId: number, number: number): string {
    return `/api/tenders/${tenderId}/certifications/${number}/pdf`;
  }

  pick(month: string): void {
    if (/^\d{4}-\d{2}$/.test(month)) {
      this.feedback.set('');
      this.load(Number(this.id()), month);
    }
  }

  monthLabel(month: string): string {
    const [y, m] = month.split('-').map(Number);
    const text = new Date(y, m - 1, 1).toLocaleDateString('es-ES', { month: 'long', year: 'numeric' });
    return text.charAt(0).toUpperCase() + text.slice(1);
  }

  issue(): void {
    const p = this.preview();
    if (!p) return;
    this.busy.set(true);
    this.api.issueCertification(p.tenderId, p.result.month).subscribe({
      next: (c) => {
        this.busy.set(false);
        this.feedback.set(`Certificación ${c.number} emitida y auditada.`);
        this.load(p.tenderId);
      },
      error: (e) => {
        this.busy.set(false);
        const why = (e?.error?.details ?? []).map((d: string | { message?: string }) => (typeof d === 'string' ? d : d.message))
          .filter(Boolean).join('; ');
        this.feedback.set(e?.status === 401 ? 'La sesión ha caducado: vuelve a entrar.'
          : `El núcleo no la emite: ${why || e?.error?.message || e?.status}`);
      },
    });
  }
}
