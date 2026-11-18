import { JsonPipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api, AuditEntry, Outcome, Proposal, TYPE_LABELS } from '../api';

interface Bar { code: string; name: string; start: number; width: number; critical: boolean; duration: number; }

@Component({
  selector: 'app-proposal-detail',
  imports: [RouterLink, FormsModule, JsonPipe],
  template: `
    <a routerLink="/" class="back">← Bandeja</a>
    @if (error()) { <p class="error glass" role="alert">{{ error() }}</p> }
    @if (p(); as p) {
      <header class="head reveal">
        <p class="eyebrow">{{ typeLabel() }} · {{ p.tenderCode }} · #{{ p.id }}</p>
        <h1>{{ title() }}</h1>
        <div class="badges">
          <span class="pill" data-testid="status" [class.ok]="p.status === 'EXECUTED'"
                [class.fail]="p.status === 'RULES_FAILED' || p.status === 'REJECTED'"
                [class.warn]="p.status === 'PENDING_REVIEW'">{{ statusLabel(p.status) }}</span>
          @for (r of p.routingReasons; track r) {
            <span class="pill" [class.fail]="r === 'INJECTION_DETECTED'">{{ r }}</span>
          }
        </div>
        @if (injection()) {
          <p class="banner" role="note">Los documentos contienen texto que parece una instrucción dirigida a un sistema automático. Se trata como dato: no se ha obedecido.</p>
        }
      </header>

      <div class="grid">
        <section class="panel glass reveal" aria-labelledby="facts">
          <h2 id="facts">Hechos</h2>
          @switch (p.type) {
            @case ('SCHEDULE') {
              <div class="gantt" role="img" [attr.aria-label]="'Planning de ' + bars().length + ' actividades; en verde la ruta crítica'">
                @for (b of bars(); track b.code) {
                  <div class="g-row">
                    <span class="g-label">{{ b.code }} <small>{{ b.name }}</small></span>
                    <span class="g-lane"><span class="g-bar" [class.crit]="b.critical"
                      [style.left.%]="b.start" [style.width.%]="b.width" [title]="b.duration + ' días'"></span></span>
                  </div>
                }
              </div>
              <p class="note">Duración {{ duration() }} días laborables · en verde, ruta crítica calculada por el núcleo.</p>
            }
            @case ('BID_ANALYSIS') {
              <dl class="facts">
                @for (f of fieldList(); track f[0]) { <dt>{{ f[0] }}</dt><dd>{{ f[1] }}</dd> }
              </dl>
            }
            @case ('TAKEOFF') {
              <table><thead><tr><th scope="col">Partida</th><th scope="col">Unidad</th><th scope="col">Cantidad</th></tr></thead>
                <tbody>@for (i of p.payload.items; track i.code) { <tr><td>{{ i.code }}</td><td>{{ i.unit }}</td><td>{{ i.quantity }}</td></tr> }</tbody></table>
            }
            @case ('BUDGET') {
              <p class="big">{{ p.payload.claimedTotal }} €</p>
              <p class="note">Total calculado por el núcleo con las tarifas de la empresa, IVA excluido.</p>
            }
            @case ('REPORT') {
              @if (p.payload.sentences) {
                <ol class="sentences">
                  @for (s of p.payload.sentences; track $index) {
                    <li>{{ s.text }} <span class="src">{{ s.sources.join(', ') }}</span></li>
                  }
                </ol>
              } @else {
                <p class="big">{{ p.payload.recommendation }}</p>
                <ul>@for (r of p.payload.reasons; track r) { <li>{{ r }}</li> }</ul>
              }
            }
            @default { <pre class="json">{{ p.payload | json }}</pre> }
          }
        </section>

        <section class="panel glass reveal" aria-labelledby="rules">
          <h2 id="rules">Reglas aplicadas</h2>
          <ol class="rules">
            @for (r of p.ruleResults; track r.rule) {
              <li><span class="pill" [class.ok]="r.outcome === 'PASS'" [class.warn]="r.outcome === 'WARN'"
                        [class.fail]="r.outcome === 'FAIL'" [class.skip]="r.outcome === 'SKIP'">{{ r.outcome }}</span>
                <strong>{{ r.rule }}</strong> <span class="msg">{{ r.message }}</span></li>
            }
          </ol>
        </section>

        <section class="panel glass reveal wide" aria-labelledby="cites">
          <h2 id="cites">Citas ({{ p.citations.length }})</h2>
          <div class="cites">
            @for (c of p.citations.slice(0, citeLimit()); track $index) {
              <blockquote class="cite"><span class="where">{{ c.document }} · pág. {{ c.page }}</span>{{ c.text }}</blockquote>
            }
          </div>
          @if (p.citations.length > citeLimit()) {
            <button class="btn btn-ghost" type="button" (click)="citeLimit.set(p.citations.length)">Ver todas</button>
          }
        </section>

        <section class="panel glass reveal wide" aria-labelledby="audit">
          <h2 id="audit">Auditoría</h2>
          <ol class="audit">
            @for (a of trail(); track a.seq) {
              <li><span class="seq">#{{ a.seq }}</span> {{ a.action }} <span class="muted">· {{ a.actorRole }} · {{ a.createdAt }}</span>
                <code class="hash" [title]="a.hash">{{ a.hash.slice(0, 12) }}…</code></li>
            }
          </ol>
        </section>
      </div>

      @if (p.status === 'PENDING_REVIEW') {
        <form class="decide glass reveal" (ngSubmit)="submit()" aria-labelledby="decide-title">
          <h2 id="decide-title">Tu decisión</h2>
          <label for="comment">Comentario</label>
          <textarea id="comment" name="comment" rows="2" [(ngModel)]="comment" placeholder="Motivo de la decisión"></textarea>
          @if (outcome() === 'CORRECT') {
            <label for="corrected">Carga corregida (JSON)</label>
            <textarea id="corrected" name="corrected" rows="8" [(ngModel)]="corrected" class="mono"></textarea>
          }
          <div class="actions" role="group" aria-label="Decisión">
            <button class="btn btn-approve" type="submit" data-testid="approve" [disabled]="busy()" (click)="outcome.set('APPROVE')">Aprobar</button>
            <button class="btn btn-correct" type="button" [disabled]="busy()" (click)="startCorrect()">
              {{ outcome() === 'CORRECT' ? 'Enviar corrección' : 'Corregir' }}</button>
            <button class="btn btn-reject" type="submit" [disabled]="busy()" (click)="outcome.set('REJECT')">Rechazar</button>
          </div>
          <p class="note" aria-live="polite">{{ feedback() }}</p>
        </form>
      }
    }
  `,
  styles: `
    .back { display: inline-block; margin: 32px 8px 0; color: var(--muted); text-decoration: none; }
    .head { padding: 28px 8px 8px; }
    h1 { font-size: clamp(2rem, 4.5vw, 3.6rem); letter-spacing: -0.035em; line-height: 1.05; margin: 10px 0 16px; }
    .badges { display: flex; flex-wrap: wrap; gap: 8px; }
    .banner { margin-top: 18px; padding: 14px 18px; border-radius: 14px; background: rgba(255,61,113,.12); border: 1px solid rgba(255,61,113,.5); }
    .grid { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr); gap: 18px; margin-top: 24px; }
    @media (max-width: 900px) { .grid { grid-template-columns: 1fr; } }
    .panel { padding: 24px; min-width: 0; }
    .wide { grid-column: 1 / -1; }
    h2 { margin: 0 0 16px; font-size: 1.15rem; }
    .gantt { display: grid; gap: 8px; }
    .g-row { display: grid; grid-template-columns: 170px 1fr; align-items: center; gap: 10px; }
    .g-label { font-weight: 700; font-size: .9rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .g-label small { color: var(--muted); font-weight: 400; }
    .g-lane { position: relative; height: 16px; background: var(--surface); border-radius: 999px; }
    .g-bar { position: absolute; top: 0; height: 100%; border-radius: 999px; background: linear-gradient(90deg, var(--accent-2), #a993ff);
      transform-origin: left; animation: grow .9s var(--ease) both; }
    .g-bar.crit { background: linear-gradient(90deg, var(--accent), #b6ff8f); box-shadow: 0 0 18px rgba(83,252,24,.45); }
    @keyframes grow { from { transform: scaleX(0); } to { transform: scaleX(1); } }
    .facts { display: grid; grid-template-columns: max-content 1fr; gap: 6px 18px; margin: 0; }
    dt { color: var(--muted); } dd { margin: 0; font-weight: 600; }
    table { width: 100%; border-collapse: collapse; } th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--border); }
    th { color: var(--muted); font-weight: 600; }
    .big { font-size: 2.6rem; font-weight: 800; letter-spacing: -0.03em; margin: 0; }
    .note, .muted, .msg { color: var(--muted); }
    .rules, .audit { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
    .rules li { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 10px; }
    .rules .msg { flex-basis: 100%; font-size: .9rem; }
    .cites { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 12px; margin-bottom: 12px; }
    .cite { margin: 0; padding: 14px 16px; border-left: 3px solid var(--accent-2); background: var(--surface); border-radius: 12px; }
    .where { display: block; font-size: .75rem; color: var(--accent); text-transform: uppercase; letter-spacing: .12em; margin-bottom: 6px; }
    .sentences li { margin-bottom: 8px; } .src { display: block; color: var(--muted); font-size: .8rem; }
    .seq { font-weight: 700; } .hash { margin-left: 8px; color: var(--muted); }
    .json { white-space: pre-wrap; word-break: break-word; font-size: .85rem; color: var(--muted); }
    .decide { margin-top: 24px; padding: 22px 24px; display: grid; gap: 10px; box-shadow: var(--shadow);
      border-color: rgba(83,252,24,.35); }
    label { font-weight: 600; } textarea { width: 100%; background: var(--bg-2); color: var(--text); border: 1px solid var(--border); border-radius: 12px; padding: 10px 12px; font: inherit; }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .85rem; }
    .actions { display: flex; flex-wrap: wrap; gap: 12px; }
    .error { padding: 18px 22px; margin: 24px 8px; }
  `,
})
export class ProposalDetail {
  private readonly api = inject(Api);
  readonly id = input.required<string>();
  readonly p = signal<Proposal | null>(null);
  readonly allAudit = signal<AuditEntry[]>([]);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly feedback = signal('');
  readonly outcome = signal<Outcome>('APPROVE');
  readonly citeLimit = signal(6);
  comment = '';
  corrected = '';

  readonly typeLabel = computed(() => TYPE_LABELS[this.p()?.type ?? ''] ?? this.p()?.type ?? '');
  readonly injection = computed(() => !!this.p()?.routingReasons.includes('INJECTION_DETECTED'));
  readonly trail = computed(() => this.allAudit().filter((a) => a.entityType === 'proposal' && a.entityId === String(this.p()?.id)));
  readonly fieldList = computed(() => Object.entries((this.p()?.payload?.fields ?? {}) as Record<string, string>));
  readonly duration = computed(() => (this.p()?.ruleResults.find((r) => r.rule === 'CRITICAL_PATH')?.details?.['duration'] as number) ?? 0);
  readonly bars = computed<Bar[]>(() => scheduleBars(this.p()));
  readonly title = computed(() => {
    const p = this.p();
    if (!p) return '';
    return p.payload?.title ?? p.payload?.description ?? `${this.typeLabel()} de ${p.tenderCode}`;
  });

  constructor() {
    effect(() => this.load(Number(this.id())));
  }

  private load(id: number): void {
    this.api.proposal(id).subscribe({
      next: (p) => { this.p.set(p); this.corrected = JSON.stringify(p.payload, null, 2); },
      error: (e) => this.error.set(e?.status === 404 ? 'La propuesta no existe.' : 'No se pudo cargar la propuesta.'),
    });
    this.api.audit().subscribe({ next: (a) => this.allAudit.set(a) });
  }

  statusLabel(s: Proposal['status']): string {
    return { PENDING_REVIEW: 'Pendiente de revisión', EXECUTED: 'Ejecutada', REJECTED: 'Rechazada', RULES_FAILED: 'Rechazada por reglas' }[s];
  }

  startCorrect(): void {
    if (this.outcome() !== 'CORRECT') {
      this.outcome.set('CORRECT');
      return;
    }
    this.submit();
  }

  submit(): void {
    const p = this.p();
    if (!p) return;
    let payload: unknown;
    if (this.outcome() === 'CORRECT') {
      try {
        payload = JSON.parse(this.corrected);
      } catch {
        this.feedback.set('La carga corregida no es un JSON válido.');
        return;
      }
    }
    this.busy.set(true);
    this.api.decide(p.id, this.outcome(), this.comment, payload).subscribe({
      next: (updated) => {
        this.p.set(updated);
        this.busy.set(false);
        this.feedback.set('Decisión registrada y auditada.');
        this.api.audit().subscribe({ next: (a) => this.allAudit.set(a) });
      },
      error: (e) => {
        this.busy.set(false);
        this.feedback.set(e?.status === 422 ? 'Las reglas rechazan la corrección: revisa los datos.' : `Error ${e?.status ?? ''}`);
      },
    });
  }
}

/** Bars for a schedule payload using the critical-path result the core attached. */
export function scheduleBars(p: Proposal | null): Bar[] {
  if (!p || p.type !== 'SCHEDULE') return [];
  const acts: { code: string; name?: string; duration: number }[] = p.payload?.activities ?? [];
  const links: { from: string; to: string; lag: number }[] = p.payload?.links ?? [];
  const es = new Map<string, number>();
  const dur = new Map(acts.map((a) => [a.code, a.duration]));
  // forward pass in link order until stable (display only; the core owns the real CPM)
  for (let i = 0; i < acts.length; i++) {
    for (const a of acts) {
      const start = Math.max(0, ...links.filter((l) => l.to === a.code).map((l) => (es.get(l.from) ?? 0) + (dur.get(l.from) ?? 0) + l.lag));
      es.set(a.code, start);
    }
  }
  const end = Math.max(1, ...acts.map((a) => (es.get(a.code) ?? 0) + a.duration));
  const critical = new Set((p.ruleResults.find((r) => r.rule === 'CRITICAL_PATH')?.details?.['critical'] as string[]) ?? []);
  return acts.map((a) => ({
    code: a.code, name: a.name ?? '', duration: a.duration, critical: critical.has(a.code),
    start: ((es.get(a.code) ?? 0) / end) * 100, width: (a.duration / end) * 100,
  }));
}
