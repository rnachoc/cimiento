import { Routes } from '@angular/router';
import { Inbox } from './inbox/inbox';
import { Dashboard } from './dashboard/dashboard';
import { Portfolio } from './portfolio/portfolio';
import { Site } from './site/site';
import { TenderFile } from './tender/tender-file';
import { ProposalDetail } from './proposal/proposal-detail';

export const routes: Routes = [
  { path: '', component: Inbox, title: 'Cimiento · Revisión' },
  { path: 'cartera', component: Portfolio, title: 'Cimiento · Cartera' },
  { path: 'licitaciones/:id', component: TenderFile, title: 'Cimiento · Licitación' },
  { path: 'obra/:id', component: Site, title: 'Cimiento · Obra' },
  { path: 'direccion', component: Dashboard, title: 'Cimiento · Dirección' },
  { path: 'proposals/:id', component: ProposalDetail, title: 'Cimiento · Propuesta' },
  { path: '**', redirectTo: '' },
];
