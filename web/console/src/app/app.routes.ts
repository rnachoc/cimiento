import { Routes } from '@angular/router';
import { Inbox } from './inbox/inbox';
import { ProposalDetail } from './proposal/proposal-detail';

export const routes: Routes = [
  { path: '', component: Inbox, title: 'Cimiento · Revisión' },
  { path: 'proposals/:id', component: ProposalDetail, title: 'Cimiento · Propuesta' },
  { path: '**', redirectTo: '' },
];
