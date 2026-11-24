import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { IntakeView } from '../api';
import { Session } from '../session';
import { formProblems, NewTender, uploadBlock } from './new-tender';

const pdf = (name = 'pliego.pdf', size = 10) => new File([new Uint8Array(size)], name, { type: 'application/pdf' });
const JOB: IntakeView = {
  id: 4, code: 'LIC-14', title: 'Reforma del mercado', plannedStart: '2027-02-01', status: 'QUEUED', documents: 1, bytes: 10,
  uploadedBy: 'estudios', createdAt: 't', startedAt: null, finishedAt: null, tenderId: null, proposals: null, message: null,
};

describe('new tender form rules', () => {
  it('lets estimating and management upload, not site managers', () => {
    expect(uploadBlock(undefined)).toContain('Inicia sesión');
    expect(uploadBlock('SITE_MANAGER')).toContain('Jefatura de obra');
    expect(uploadBlock('ESTIMATING')).toBe('');
    expect(uploadBlock('MANAGEMENT')).toBe('');
  });

  it('checks code, title, a Monday start and the PDFs before sending', () => {
    expect(formProblems('LIC-14', 'Mercado', '2027-02-01', { pliego: pdf() })).toEqual([]);
    const p = formProblems('../x', ' ', '2027-02-02', { pliego: null, anexo: pdf('anexo.docx') });
    expect(p.join(' ')).toContain('El código');
    expect(p.join(' ')).toContain('título');
    expect(p.join(' ')).toContain('lunes');
    expect(p.join(' ')).toContain('Adjunta el pliego');
    expect(p.join(' ')).toContain('Anexo: tiene que ser un PDF');
    expect(formProblems('A', 'T', '2027-02-01', { pliego: pdf('p.pdf', 21 * 1024 * 1024) }).join(' ')).toContain('20 MB');
  });
});

describe('NewTender', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [NewTender],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(Session).person.set({ username: 'estudios', displayName: 'Luis Ferrer', role: 'ESTIMATING', scope: [] });
  });

  afterEach(() => http.verify());

  it('uploads the documents as a form and follows the job until the agents finish', async () => {
    const fixture = TestBed.createComponent(NewTender);
    http.expectOne('/api/intakes').flush([]);
    await fixture.whenStable();
    const c = fixture.componentInstance;
    c.code = 'LIC-14'; c.title = 'Reforma del mercado'; c.start = '2027-02-01';
    c.pick('pliego', pdf());
    c.send();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/intakes');
    const form = req.request.body as FormData;
    expect(form.get('code')).toBe('LIC-14');
    expect(form.get('plannedStart')).toBe('2027-02-01');
    expect((form.get('pliego') as File).name).toBe('pliego.pdf');
    expect(form.get('anexo')).toBeNull();
    req.flush(JOB);
    expect(c.feedback()).toContain('LIC-14 recibida');
    http.expectOne('/api/intakes').flush([{ ...JOB, status: 'DONE', tenderId: 9, proposals: 12 }]);
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="job-LIC-14"]')?.textContent).toContain('Lista para revisar');
    expect(el.querySelector('[data-testid="open-LIC-14"]')?.getAttribute('href')).toBe('/licitaciones/9');
  });

  it('shows the core reasons when it refuses the upload', async () => {
    const fixture = TestBed.createComponent(NewTender);
    http.expectOne('/api/intakes').flush([]);
    const c = fixture.componentInstance;
    c.code = 'LIC-14'; c.title = 'Mercado'; c.start = '2027-02-01';
    c.pick('pliego', pdf());
    c.send();
    http.expectOne((r) => r.method === 'POST').flush({ message: 'conflict' , details: ['a tender with code LIC-14 already exists'] },
      { status: 409, statusText: 'Conflict' });
    expect(c.problems()).toEqual(['a tender with code LIC-14 already exists']);
  });
});
