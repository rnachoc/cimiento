import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { firstValueFrom, Observable } from 'rxjs';
import { App } from './app';
import { Login } from './login/login';
import { Session, signedIn } from './session';

describe('Session', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App, Login],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends people without a session to the sign-in page and remembers where they were going', async () => {
    const result = TestBed.runInInjectionContext(() => signedIn({} as never, { url: '/cartera' } as never)) as Observable<boolean | UrlTree>;
    const answer = firstValueFrom(result);
    http.expectOne('/api/session/me').flush({ error: 'unauthorized' }, { status: 401, statusText: 'Unauthorized' });
    const tree = (await answer) as UrlTree;
    expect(TestBed.inject(Router).serializeUrl(tree)).toBe('/acceso?volver=%2Fcartera');
  });

  it('signs in through the core, which answers with the person (the token never stays in the page)', async () => {
    const fixture = TestBed.createComponent(Login);
    const cmp = fixture.componentInstance;
    cmp.username = 'obra';
    cmp.token = 'secreto';
    cmp.submit();
    const req = http.expectOne('/api/session');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'obra', token: 'secreto' });
    req.flush({ username: 'obra', displayName: 'Ana Obra', role: 'SITE_MANAGER', scope: ['SCHEDULE'] });
    expect(cmp.token).toBe('');
    expect(TestBed.inject(Session).person()?.displayName).toBe('Ana Obra');
  });

  it('shows who is signed in and their role in the header', async () => {
    TestBed.inject(Session).person.set({ username: 'gerencia', displayName: 'Marta Gerencia', role: 'MANAGEMENT', scope: [] });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="who"]')?.textContent).toContain('Marta Gerencia');
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="who"]')?.textContent).toContain('Gerencia');
  });
});
