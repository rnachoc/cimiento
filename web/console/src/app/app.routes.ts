import { Routes } from '@angular/router';
import { Inbox } from './inbox/inbox';
import { Dashboard } from './dashboard/dashboard';
import { Portfolio } from './portfolio/portfolio';
import { Site } from './site/site';
import { TenderFile } from './tender/tender-file';
import { ProposalDetail } from './proposal/proposal-detail';
import { Login } from './login/login';
import { Certifications } from './certification/certification';
import { signedIn } from './session';

export const routes: Routes = [
  { path: 'acceso', component: Login, title: 'Cimiento · Acceso' },
  { path: '', component: Inbox, title: 'Cimiento · Revisión', canActivate: [signedIn] },
  { path: 'cartera', component: Portfolio, title: 'Cimiento · Cartera', canActivate: [signedIn] },
  { path: 'licitaciones/:id', component: TenderFile, title: 'Cimiento · Licitación', canActivate: [signedIn] },
  { path: 'obra/:id', component: Site, title: 'Cimiento · Obra', canActivate: [signedIn] },
  { path: 'certificaciones/:id', component: Certifications, title: 'Cimiento · Certificaciones', canActivate: [signedIn] },
  { path: 'direccion', component: Dashboard, title: 'Cimiento · Dirección', canActivate: [signedIn] },
  { path: 'proposals/:id', component: ProposalDetail, title: 'Cimiento · Propuesta', canActivate: [signedIn] },
  { path: '**', redirectTo: '' },
];
