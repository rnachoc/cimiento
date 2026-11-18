import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Session } from '../session';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  template: `
    <section class="login glass reveal" aria-labelledby="login-title">
      <h1 id="login-title">Acceso</h1>
      <p class="muted">Cada decisión queda firmada por la persona que la toma. Usa tu usuario y tu token personal.</p>
      <form (ngSubmit)="submit()">
        <label for="username">Usuario</label>
        <input id="username" name="username" autocomplete="username" required [(ngModel)]="username" />
        <label for="token">Token personal</label>
        <input id="token" name="token" type="password" autocomplete="current-password" required [(ngModel)]="token" />
        <button class="btn btn-approve" type="submit" data-testid="sign-in" [disabled]="busy()">Entrar</button>
        <p class="note" role="alert" aria-live="assertive">{{ error() }}</p>
      </form>
    </section>
  `,
  styles: `
    .login { max-width: 420px; margin: 72px auto 0; padding: 32px; }
    h1 { margin: 0 0 8px; font-size: 2.2rem; letter-spacing: -0.03em; }
    form { display: grid; gap: 8px; margin-top: 20px; }
    label { font-weight: 600; font-size: .9rem; margin-top: 8px; }
    input { padding: 12px 14px; border-radius: 12px; border: 1px solid var(--border); background: var(--surface); color: var(--text); font: inherit; }
    button { margin-top: 16px; }
    .note { min-height: 1.4em; color: var(--danger, #ff3d71); }
  `,
})
export class Login {
  private readonly session = inject(Session);
  private readonly router = inject(Router);
  readonly volver = input<string>();
  readonly busy = signal(false);
  readonly error = signal('');
  username = '';
  token = '';

  submit(): void {
    this.busy.set(true);
    this.error.set('');
    this.session.signIn(this.username.trim(), this.token).subscribe({
      next: () => {
        this.token = '';
        const back = this.volver();
        // only same-app paths, never an absolute URL
        this.router.navigateByUrl(back && back.startsWith('/') && !back.startsWith('//') ? back : '/');
      },
      error: () => {
        this.busy.set(false);
        this.error.set('Usuario o token incorrectos.');
      },
    });
  }
}
