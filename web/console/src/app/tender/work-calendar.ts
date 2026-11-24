import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, CalendarKind, CalendarView, Holiday } from '../api';
import { Session } from '../session';

/** Why this person cannot change the calendar ('' when allowed); the core decides anyway. */
export function calendarBlock(role: string | undefined): string {
  if (!role) return 'Inicia sesión para cambiarlo.';
  if (role === 'ESTIMATING') return 'Estudios no cambia el calendario de obra.';
  return '';
}

/** Working days lost between two dates of the same schedule (both are working days of their calendar). */
export function shiftText(view: CalendarView | null): string {
  if (!view?.endStandard || !view.endWithCalendar || view.endStandard === view.endWithCalendar) return '';
  const n = view.holidaysInSpan.length;
  return `${n} festivo${n === 1 ? '' : 's'} en días laborables: el fin pasa del ${fmt(view.endStandard)} al ${fmt(view.endWithCalendar)}.`;
}

const fmt = (iso: string) => {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('es-ES', { day: 'numeric', month: 'short', year: 'numeric' });
};

/** Work calendar of a tender: national holidays, local holidays and what they do to the schedule end. */
@Component({
  selector: 'app-work-calendar',
  imports: [FormsModule],
  template: `
    <section class="panel glass reveal wide" aria-labelledby="f-cal" data-testid="work-calendar">
      <h2 id="f-cal">Calendario laboral</h2>
      @if (view(); as v) {
        <p class="muted">Lunes a viernes. Los festivos que caen en día laborable retrasan el planning; el plazo del contrato sigue en meses naturales.</p>
        <div class="row">
          <label for="cal-kind">Festivos</label>
          <select id="cal-kind" name="kind" [(ngModel)]="kind" data-testid="cal-kind" [disabled]="!!block()">
            <option value="NONE">Sin festivos</option>
            <option value="ES_NATIONAL">Nacionales de España</option>
          </select>
        </div>

        <h3>Festivos locales y autonómicos</h3>
        <ul class="local">
          @for (h of local(); track $index) {
            <li>
              <input type="date" [ngModel]="h.date" (ngModelChange)="edit($index, 'date', $event)" [name]="'d' + $index" aria-label="Fecha" [disabled]="!!block()" />
              <input type="text" [ngModel]="h.name" (ngModelChange)="edit($index, 'name', $event)" [name]="'n' + $index" maxlength="80"
                     placeholder="Nombre del festivo" aria-label="Nombre" [disabled]="!!block()" />
              <button class="btn btn-ghost small" type="button" (click)="remove($index)" [disabled]="!!block()" aria-label="Quitar festivo">✕</button>
            </li>
          } @empty { <li class="muted">Ninguno.</li> }
        </ul>
        <div class="row">
          <button class="btn btn-ghost small" type="button" (click)="add()" [disabled]="!!block() || local().length >= 30" data-testid="cal-add">+ Añadir festivo</button>
          <button class="btn btn-approve small" type="button" (click)="save()" [disabled]="busy() || !!block()" data-testid="cal-save">Guardar calendario</button>
          @if (block()) { <span class="muted">{{ block() }}</span> }
          <span class="note" aria-live="polite">{{ feedback() }}</span>
        </div>
        @if (problems().length) {
          <ul class="problems" role="alert">@for (p of problems(); track $index) { <li>{{ p }}</li> }</ul>
        }

        <h3>Festivos dentro de la obra</h3>
        @if (v.holidaysInSpan.length) {
          <ul class="days" data-testid="cal-days">
            @for (h of v.holidaysInSpan; track h.date) { <li><b>{{ day(h.date) }}</b> {{ h.name }}</li> }
          </ul>
        } @else { <p class="muted">Ninguno entre el inicio y el fin.</p> }
        @if (shift()) { <p class="shift" data-testid="cal-shift">{{ shift() }}</p> }
      } @else if (error()) {
        <p class="muted">{{ error() }}</p>
      }
    </section>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .panel { padding: 24px; min-width: 0; }
    h2 { margin: 0 0 16px; font-size: 1.15rem; }
    h3 { font-size: .95rem; margin: 18px 0 8px; color: var(--muted); font-weight: 600; }
    .row { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 10px; }
    select, input { padding: 8px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--surface); color: var(--text); font: inherit; color-scheme: dark; }
    input[type=text] { flex: 1; min-width: 160px; }
    .local { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
    .local li { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
    .days { margin: 0; padding-left: 18px; columns: 2 220px; }
    .days li { margin: 4px 0; }
    .shift { margin-top: 12px; padding: 10px 14px; border-radius: 12px; background: rgba(255,176,32,.12); border: 1px solid rgba(255,176,32,.5); }
    .btn.small { padding: 8px 14px; font-size: .85rem; }
    .problems { margin: 12px 0 0; padding: 10px 14px 10px 28px; border-radius: 12px; background: rgba(255,61,113,.12); color: #ffd0dc; font-size: .88rem; }
    .muted { color: var(--muted); }
  `,
})
export class WorkCalendar {
  private readonly api = inject(Api);
  private readonly session = inject(Session);
  readonly tenderId = input.required<number>();
  readonly view = signal<CalendarView | null>(null);
  readonly local = signal<Holiday[]>([]);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly feedback = signal('');
  readonly problems = signal<string[]>([]);
  kind: CalendarKind = 'NONE';

  readonly block = computed(() => calendarBlock(this.session.person()?.role));
  readonly shift = computed(() => shiftText(this.view()));

  constructor() {
    effect(() => this.load(this.tenderId()));
  }

  private show(v: CalendarView): void {
    this.view.set(v);
    this.kind = v.calendar;
    this.local.set(v.localHolidays.map((h) => ({ ...h })));
  }

  private load(id: number): void {
    this.api.calendar(id).subscribe({
      next: (v) => this.show(v),
      error: () => this.error.set('No se pudo cargar el calendario.'),
    });
  }

  day(iso: string): string { return fmt(iso); }

  add(): void { this.local.update((l) => [...l, { date: '', name: '' }]); }
  remove(i: number): void { this.local.update((l) => l.filter((_, j) => j !== i)); }
  edit(i: number, field: 'date' | 'name', value: string): void {
    this.local.update((l) => l.map((h, j) => (j === i ? { ...h, [field]: value } : h)));
  }

  save(): void {
    this.problems.set([]);
    this.feedback.set('');
    const rows = this.local().filter((h) => h.date || h.name.trim());
    if (rows.some((h) => !h.date || !h.name.trim())) {
      this.problems.set(['Cada festivo local necesita fecha y nombre.']);
      return;
    }
    this.busy.set(true);
    this.api.setCalendar(this.tenderId(), this.kind, rows).subscribe({
      next: (v) => { this.busy.set(false); this.show(v); this.feedback.set('Calendario guardado y auditado.'); },
      error: (e) => {
        this.busy.set(false);
        const d = e?.error?.details;
        this.problems.set(Array.isArray(d) && d.length
          ? d.map((x: string | { message: string }) => (typeof x === 'string' ? x : x.message))
          : [e?.status === 401 ? 'La sesión ha caducado: vuelve a entrar.' : e?.error?.message ?? `HTTP ${e?.status ?? '?'}`]);
      },
    });
  }
}
