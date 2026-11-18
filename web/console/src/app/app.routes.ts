import { Routes } from '@angular/router';
import { Inbox } from './inbox/inbox';
import { Portfolio } from './portfolio/portfolio';
import { ProposalDetail } from './proposal/proposal-detail';

export const routes: Routes = [
  { path: '', component: Inbox, title: 'Cimiento · Revisión' },
  { path: 'cartera', component: Portfolio, title: 'Cimiento · Cartera' },
  { path: 'proposals/:id', component: ProposalDetail, title: 'Cimiento · Propuesta' },
  { path: '**', redirectTo: '' },
];
