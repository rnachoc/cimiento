import { Component, computed, inject, OnDestroy, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api, IntakeView } from '../api';
import { Session } from '../session';

export const DOCS: { name: 'pliego' | 'anexo' | 'mediciones'; label: string; hint: string; required: boolean }[] = [
  { name: 'pliego', label: 'Pliego', hint: 'Pliego de cláusulas o de condiciones', required: true },
  { name: 'anexo', label: 'Anexo', hint: 'Programa de trabajo u otros anexos', required: false },
  { name: 'mediciones', label: 'Mediciones', hint: 'Mediciones o presupuesto del proyecto', required: false },
];
const MAX_MB = 20;

/** Why this person cannot upload ('' when allowed); the core decides anyway. */
export function uploadBlock(role: string | undefined): string {
  if (!role) return 'Inicia sesión para subir licitaciones.';
  if (role === 'SITE_MANAGER') return 'Jefatura de obra no da de alta licitaciones.';
  return '';
}

/** The problems a form has before anything is sent ('' fields are fine until submit). */
export function formProblems(code: string, title: string, start: string, files: Record<string, File | null>): string[] {
  const out: string[] = [];
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/.test(code.trim())) out.push('El código admite letras, números, punto, guion y guion bajo (hasta 40).');
  if (!title.trim()) out.push('Escribe el título de la licitación.');
  if (!start) out.push('Elige la fecha de inicio prevista.');
  else {
    const [y, m, d] = start.split('-').map(Number);
    if (new Date(y, m - 1, d).getDay() !== 1) out.push('El inicio previsto tiene que ser un lunes.');
  }
  if (!files['pliego']) out.push('Adjunta el pliego.');
  for (const doc of DOCS) {
    const f = files[doc.name];
    if (!f) continue;
    if (!f.name.toLowerCase().endsWith('.pdf')) out.push(`${doc.label}: tiene que ser un PDF.`);
    if (f.size > MAX_MB * 1024 * 1024) out.push(`${doc.label}: pesa más de ${MAX_MB} MB.`);
  }
  return out;
}

const STATUS: Record<IntakeView['status'], string> = {
  QUEUED: 'En cola', RUNNING: 'Analizando…', DONE: 'Lista para revisar', FAILED: 'Error',
};

/** Upload a new tender: the documents go to the core, the agents analyse them, people review. */
@Component({
  selector: 'app-new-tender',
  imports: [FormsModule, RouterLink],
  template: `
    <a routerLink="/cartera" class="back">← Cartera</a>
    <section class="hero reveal" aria-labelledby="nt-title">
      <p class="eyebrow">Nueva licitación</p>
      <h1 id="nt-title">Sube los documentos y deja que <span class="accent">los agentes</span> los lean</h1>
      <p class="lede">El núcleo guarda los PDF tal como llegan. Los agentes extraen los datos con su cita, miden, presupuestan y planifican; todo llega a la bandeja para que una persona lo revise.</p>
    </section>

    <form class="panel glass reveal" (submit)="$event.preventDefault(); send()" aria-labelledby="nt-form">
      <h2 id="nt-form">Datos y documentos</h2>
      <div class="grid">
        <label>Código<input name="code" [(ngModel)]="code" maxlength="40" placeholder="LIC-2027-014" data-testid="nt-code" [disabled]="!!block()" /></label>
        <label class="wide">Título<input name="title" [(ngModel)]="title" maxlength="200" placeholder="Reforma del mercado municipal" data-testid="nt-title" [disabled]="!!block()" /></label>
        <label>Inicio previsto (lunes)<input name="start" type="date" [(ngModel)]="start" data-testid="nt-start" [disabled]="!!block()" /></label>
      </div>
      <div class="files">
        @for (d of docs; track d.name) {
          <label class="drop" [class.has]="!!files()[d.name]">
            <span class="dl">{{ d.label }}@if (d.required) { <b>*</b> }</span>
            <span class="muted">{{ files()[d.name]?.name ?? d.hint }}</span>
            @if (files()[d.name]; as f) { <span class="size">{{ mb(f.size) }}</span> }
            <input type="file" accept="application/pdf,.pdf" (change)="pick(d.name, $any($event.target).files?.[0] ?? null)"
                   [attr.data-testid]="'nt-file-' + d.name" [disabled]="!!block()" />
          </label>
        }
      </div>
      @if (problems().length) {
        <ul class="problems" role="alert" data-testid="nt-problems">@for (p of problems(); track $index) { <li>{{ p }}</li> }</ul>
      }
      <div class="row">
        <button class="btn btn-approve" type="submit" [disabled]="busy() || !!block()" data-testid="nt-send">{{ busy() ? 'Subiendo…' : 'Subir y analizar' }}</button>
        @if (block()) { <span class="muted">{{ block() }}</span> }
        <span class="note" aria-live="polite">{{ feedback() }}</span>
      </div>
    </form>

    <section class="panel glass reveal" aria-labelledby="nt-recent">
      <h2 id="nt-recent">Últimas subidas</h2>
      @if (intakes().length) {
        <ul class="jobs" data-testid="nt-jobs">
          @for (i of intakes(); track i.id) {
            <li [attr.data-testid]="'job-' + i.code">
              <span class="pill" [class.ok]="i.status === 'DONE'" [class.fail]="i.status === 'FAILED'" [class.warn]="i.status === 'RUNNING' || i.status === 'QUEUED'">{{ status(i.status) }}</span>
              <span class="jt"><b>{{ i.code }}</b> {{ i.title }}<small class="muted">{{ i.documents }} documento{{ i.documents === 1 ? '' : 's' }} · {{ i.uploadedBy }}@if (i.message) { · {{ i.message }} }</small></span>
              @if (i.status === 'DONE' && i.tenderId) {
                <a class="btn btn-ghost small" [routerLink]="['/licitaciones', i.tenderId]" [attr.data-testid]="'open-' + i.code">Ver ficha →</a>
                <a class="btn btn-ghost small" routerLink="/">Revisar</a>
              }
            </li>
          }
        </ul>
      } @else { <p class="muted">Todavía no se ha subido ninguna.</p> }
    </section>
  `,
  styles: `
    .back { display: inline-block; margin: 32px 8px 0; color: var(--muted); text-decoration: none; }
    .hero { padding: 24px 8px 12px; }
    h1 { font-size: clamp(1.9rem, 4.6vw, 3.4rem); line-height: 1.05; letter-spacing: -0.035em; margin: 10px 0 14px; max-width: 22ch; }
    .accent { color: var(--accent); }
    .lede { color: var(--muted); max-width: 70ch; margin: 0; }
    .panel { padding: 24px; margin: 18px 0; }
    h2 { margin: 0 0 16px; font-size: 1.15rem; }
    .grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 2fr) minmax(0, 1fr); gap: 14px; }
    @media (max-width: 760px) { .grid { grid-template-columns: 1fr; } }
    label { display: flex; flex-direction: column; gap: 6px; font-size: .9rem; color: var(--muted); }
    input { padding: 10px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--surface); color: var(--text); font: inherit; color-scheme: dark; }
    .files { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; margin-top: 18px; }
    .drop { position: relative; padding: 18px; border-radius: 16px; border: 1.5px dashed var(--border); cursor: pointer; min-height: 96px; transition: border-color .2s; }
    .drop:hover, .drop.has { border-color: var(--accent); }
    .drop input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
    .dl { color: var(--text); font-weight: 700; font-size: 1rem; }
    .dl b { color: var(--accent-3); }
    .size { font-size: .8rem; color: var(--accent); }
    .row { display: flex; flex-wrap: wrap; align-items: center; gap: 14px; margin-top: 18px; }
    .problems { margin: 16px 0 0; padding: 10px 14px 10px 28px; border-radius: 12px; background: rgba(255,61,113,.12); color: #ffd0dc; font-size: .9rem; }
    .jobs { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
    .jobs li { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--border); }
    .jt { flex: 1; min-width: 200px; display: flex; flex-direction: column; }
    .btn.small { padding: 8px 14px; font-size: .85rem; }
    .muted { color: var(--muted); }
  `,
})
export class NewTender implements OnDestroy {
  private readonly api = inject(Api);
  private readonly session = inject(Session);
  readonly docs = DOCS;
  readonly files = signal<Record<string, File | null>>({ pliego: null, anexo: null, mediciones: null });
  readonly intakes = signal<IntakeView[]>([]);
  readonly problems = signal<string[]>([]);
  readonly busy = signal(false);
  readonly feedback = signal('');
  readonly block = computed(() => uploadBlock(this.session.person()?.role));
  code = '';
  title = '';
  start = '';
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.refresh();
  }

  ngOnDestroy(): void {
    if (this.timer) clearTimeout(this.timer);
  }

  /** Reloads the list; while something is queued or running, checks again in 3 seconds. */
  refresh(): void {
    this.api.intakes().subscribe({
      next: (list) => {
        this.intakes.set(list);
        if (this.timer) clearTimeout(this.timer);
        if (list.some((i) => i.status === 'QUEUED' || i.status === 'RUNNING')) {
          this.timer = setTimeout(() => this.refresh(), 3000);
        }
      },
    });
  }

  pick(name: string, file: File | null): void {
    this.files.update((f) => ({ ...f, [name]: file }));
  }

  mb(bytes: number): string {
    return `${(bytes / 1024 / 1024).toLocaleString('es-ES', { maximumFractionDigits: 1 })} MB`;
  }

  status(s: IntakeView['status']): string { return STATUS[s]; }

  send(): void {
    this.feedback.set('');
    const problems = formProblems(this.code, this.title, this.start, this.files());
    this.problems.set(problems);
    if (problems.length) return;
    const form = new FormData();
    form.append('code', this.code.trim());
    form.append('title', this.title.trim());
    form.append('plannedStart', this.start);
    for (const d of DOCS) {
      const f = this.files()[d.name];
      if (f) form.append(d.name, f, `${d.name}.pdf`);
    }
    this.busy.set(true);
    this.api.uploadIntake(form).subscribe({
      next: (i) => {
        this.busy.set(false);
        this.feedback.set(`${i.code} recibida: los agentes la están analizando.`);
        this.code = ''; this.title = ''; this.start = '';
        this.files.set({ pliego: null, anexo: null, mediciones: null });
        this.refresh();
      },
      error: (e) => {
        this.busy.set(false);
        const d = e?.error?.details;
        this.problems.set(Array.isArray(d) && d.length
          ? d.map((x: string | { message: string }) => (typeof x === 'string' ? x : x.message))
          : [e?.status === 401 ? 'La sesión ha caducado: vuelve a entrar.'
            : e?.status === 413 ? 'Los archivos pesan demasiado.'
            : e?.error?.message ?? `HTTP ${e?.status ?? '?'}`]);
      },
    });
  }
}
