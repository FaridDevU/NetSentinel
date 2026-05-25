import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'scan', pathMatch: 'full' },
  {
    path: 'scan',
    loadComponent: () => import('./pages/scan/scan').then((m) => m.ScanPage),
  },
  {
    path: 'history',
    loadComponent: () => import('./pages/history/history').then((m) => m.HistoryPage),
  },
  {
    path: 'results/:id',
    loadComponent: () => import('./pages/results/results').then((m) => m.ResultsPage),
  },
  {
    path: 'dashboard',
    loadComponent: () => import('./pages/dashboard/dashboard').then((m) => m.DashboardPage),
  },
  {
    path: 'compare',
    loadComponent: () => import('./pages/compare/compare').then((m) => m.ComparePage),
  },
  {
    path: 'assets',
    loadComponent: () => import('./pages/assets/assets').then((m) => m.AssetsPage),
  },
  {
    path: 'agent',
    loadComponent: () => import('./pages/agent/agent').then((m) => m.AgentPage),
  },
  {
    path: 'tutorial',
    loadComponent: () => import('./pages/tutorial/tutorial').then((m) => m.TutorialPage),
  },
  {
    path: 'credits',
    loadComponent: () => import('./pages/credits/credits').then((m) => m.CreditsPage),
  },
];
