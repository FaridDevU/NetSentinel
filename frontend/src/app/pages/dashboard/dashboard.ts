import { Component, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ScanService } from '../../services/scan.service';
import { UserErrorService } from '../../services/user-error.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { DashboardResponse } from '../../models/scan.models';

@Component({
  selector: 'app-dashboard',
  imports: [TranslatePipe],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class DashboardPage implements OnInit {
  data = signal<DashboardResponse | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);

  constructor(
    private scanService: ScanService,
    private userError: UserErrorService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.scanService.getDashboard().subscribe({
      next: (d) => { this.data.set(d); this.loading.set(false); },
      error: (err) => { this.error.set(this.userError.message(err, 'dashboard')); this.loading.set(false); },
    });
  }

  openResults(id: string): void {
    void this.router.navigate(['/results', id]);
  }

  severityBars(): { label: string; count: number; pct: number; cls: string }[] {
    const d = this.data();
    if (!d) return [];
    const sev = d.cvesBySeverity;
    const max = Math.max(...Object.values(sev), 1);
    return [
      { label: 'CRITICAL', count: sev['CRITICAL'] ?? 0, pct: ((sev['CRITICAL'] ?? 0) / max) * 100, cls: 'critical' },
      { label: 'HIGH',     count: sev['HIGH']     ?? 0, pct: ((sev['HIGH']     ?? 0) / max) * 100, cls: 'high' },
      { label: 'MEDIUM',   count: sev['MEDIUM']   ?? 0, pct: ((sev['MEDIUM']   ?? 0) / max) * 100, cls: 'medium' },
      { label: 'LOW',      count: sev['LOW']       ?? 0, pct: ((sev['LOW']       ?? 0) / max) * 100, cls: 'low' },
    ];
  }

  riskClass(level: string | null): string {
    return (level ?? 'info').toLowerCase();
  }

  statusClass(status: string): string {
    return status.toLowerCase();
  }

  statusKey(status: string): string {
    return `status.${status.toLowerCase()}`;
  }
}
