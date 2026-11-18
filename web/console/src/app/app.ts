import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <a class="skip" href="#main">Saltar al contenido</a>
    <header class="topbar glass">
      <a routerLink="/" class="brand" aria-label="Cimiento, inicio">
        <span class="mark" aria-hidden="true"></span>
        <span>Cimiento</span>
      </a>
      <nav class="nav" aria-label="Secciones">
        <a routerLink="/" routerLinkActive="on" [routerLinkActiveOptions]="{ exact: true }" ariaCurrentWhenActive="page">Bandeja</a>
        <a routerLink="/cartera" routerLinkActive="on" ariaCurrentWhenActive="page">Cartera</a>
      </nav>
      <span class="who" title="Las credenciales las añade el servidor; el navegador no guarda ninguna">
        Revisor
      </span>
    </header>
    <main id="main" tabindex="-1"><router-outlet /></main>
    <footer class="foot">Datos sintéticos · Constructora Brisa (ficticia) · El modelo propone, el código decide</footer>
  `,
  styles: `
    :host { display: block; min-height: 100vh; }
    .skip { position: absolute; left: -999px; top: 8px; padding: 10px 16px; background: var(--accent); color: #061200; border-radius: 10px; z-index: 10; }
    .skip:focus { left: 16px; }
    .topbar {
      position: sticky; top: 12px; z-index: 5; margin: 12px auto 0; width: min(1280px, calc(100% - 24px));
      display: flex; align-items: center; gap: 18px; padding: 12px 20px; border-radius: 999px;
    }
    .brand { display: inline-flex; align-items: center; gap: 10px; font-weight: 800; font-size: 1.15rem; letter-spacing: -0.02em; text-decoration: none; }
    .mark { width: 22px; height: 22px; border-radius: 7px; background: conic-gradient(from 200deg, var(--accent), var(--accent-2), var(--accent-3), var(--accent)); box-shadow: 0 0 24px rgba(83,252,24,.45); }
    .nav { display: flex; gap: 4px; }
    .nav a { padding: 6px 14px; border-radius: 999px; color: var(--muted); text-decoration: none; font-weight: 600; font-size: .92rem; transition: color .2s, background .2s; }
    .nav a:hover { color: var(--text); }
    .nav a.on { color: var(--text); background: var(--surface-2); }
    @media (max-width: 520px) { .topbar { gap: 8px; padding: 10px 14px; } .nav a { padding: 6px 10px; } .who { display: none; } }
    .who { margin-left: auto; font-size: .85rem; padding: 6px 14px; border-radius: 999px; background: var(--surface-2); border: 1px solid var(--border); }
    main { width: min(1280px, calc(100% - 24px)); margin: 0 auto; outline: none; }
    .foot { text-align: center; color: var(--muted); font-size: .8rem; padding: 48px 16px; }
  `,
})
export class App {}
