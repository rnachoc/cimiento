import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CalendarView } from '../api';
import { Session } from '../session';
import { calendarBlock, shiftText, WorkCalendar } from './work-calendar';

const VIEW: CalendarView = {
  tenderId: 3, tenderCode: 'E-001', calendar: 'ES_NATIONAL', localHolidays: [{ date: '2027-01-20', name: 'San Sebastián' }],
  from: '2027-01-04', to: '2027-01-26', durationDays: 15, endStandard: '2027-01-22', endWithCalendar: '2027-01-26',
  holidaysInSpan: [{ date: '2027-01-06', name: 'Epifanía del Señor' }, { date: '2027-01-20', name: 'San Sebastián' }],
};

describe('work calendar rules in the console', () => {
  it('lets site managers and management change it, not estimating', () => {
    expect(calendarBlock(undefined)).toContain('Inicia sesión');
    expect(calendarBlock('ESTIMATING')).toContain('Estudios');
    expect(calendarBlock('SITE_MANAGER')).toBe('');
    expect(calendarBlock('MANAGEMENT')).toBe('');
  });

  it('explains how the holidays move the end of the schedule', () => {
    expect(shiftText(VIEW)).toContain('2 festivos');
    expect(shiftText({ ...VIEW, endWithCalendar: '2027-01-22' })).toBe('');
    expect(shiftText(null)).toBe('');
  });
});

describe('WorkCalendar', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [WorkCalendar], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(Session).person.set({ username: 'obra', displayName: 'Ana Ríos', role: 'SITE_MANAGER', scope: [] });
  });

  afterEach(() => http.verify());

  async function render() {
    const fixture = TestBed.createComponent(WorkCalendar);
    fixture.componentRef.setInput('tenderId', 3);
    fixture.detectChanges();
    http.expectOne('/api/tenders/3/calendar').flush(VIEW);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('shows the holidays inside the work and saves the calendar through the core', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('[data-testid="cal-days"] li').length).toBe(2);
    expect(el.querySelector('[data-testid="cal-shift"]')?.textContent).toContain('2 festivos');
    (el.querySelector('[data-testid="cal-save"]') as HTMLButtonElement).click();
    const req = http.expectOne('/api/tenders/3/calendar');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ calendar: 'ES_NATIONAL', localHolidays: [{ date: '2027-01-20', name: 'San Sebastián' }] });
    req.flush(VIEW);
    await fixture.whenStable();
    expect(fixture.componentInstance.feedback()).toContain('guardado');
  });

  it('asks for a date and a name before sending a local holiday', async () => {
    const fixture = await render();
    const c = fixture.componentInstance;
    c.add();
    c.edit(1, 'date', '2027-01-21');
    c.save();
    http.expectNone('/api/tenders/3/calendar');
    expect(c.problems()[0]).toContain('fecha y nombre');
  });
});
