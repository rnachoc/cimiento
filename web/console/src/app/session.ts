import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { Person } from './api';

/**
 * The signed-in person. The core keeps the session in an HttpOnly cookie, so the browser code
 * never sees a token: it only asks the core who is signed in.
 */
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);
  readonly person = signal<Person | null>(null);
  private checked = false;

  /** Who is signed in (asks the core once, then uses the cached answer). */
  current(): Observable<Person | null> {
    if (this.checked) return of(this.person());
    return this.http.get<Person>('/api/session/me').pipe(
      catchError(() => of(null)),
      tap((p) => { this.person.set(p); this.checked = true; }),
    );
  }

  signIn(username: string, token: string): Observable<Person> {
    return this.http.post<Person>('/api/session', { username, token }).pipe(
      tap((p) => { this.person.set(p); this.checked = true; }),
    );
  }

  signOut(): Observable<void> {
    return this.http.delete<void>('/api/session').pipe(
      catchError(() => of(undefined)),
      tap(() => this.person.set(null)),
    );
  }
}

/** Every view needs a signed-in person; otherwise go to the sign-in page and come back. */
export const signedIn: CanActivateFn = (_route, state) => {
  const router = inject(Router);
  return inject(Session).current().pipe(
    map((p) => p ? true : router.createUrlTree(['/acceso'], { queryParams: { volver: state.url } })),
  );
};
