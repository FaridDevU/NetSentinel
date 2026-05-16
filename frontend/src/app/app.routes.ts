import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'scan', pathMatch: 'full' },
  {
    path: 'scan',
    loadComponent: () => import('./pages/scan/scan').then((m) => m.ScanPage),
  },
  {
    path: 'history',
    loadComponent: () =>
      import('./pages/history/history').then((m) => m.HistoryPage),
  },
  {
    path: 'results/:id',
    loadComponent: () =>
      import('./pages/results/results').then((m) => m.ResultsPage),
  },
];
