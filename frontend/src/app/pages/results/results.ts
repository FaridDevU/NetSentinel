import { Component, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { DatePipe } from '@angular/common';
import { ScanService } from '../../services/scan.service';
import { AnalysisFinding, HostDto, ScanResultsResponse } from '../../models/scan.models';

@Component({
  selector: 'app-results',
  imports: [DatePipe],
  templateUrl: './results.html',
  styleUrl: './results.scss',
})
export class ResultsPage implements OnInit {
  results = signal<ScanResultsResponse | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);
  expandedHosts = signal<Set<string>>(new Set());
  expandedPorts = signal<Set<string>>(new Set());

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private scanService: ScanService
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      void this.router.navigate(['/history']);
      return;
    }
    this.scanService.getResults(id).subscribe({
      next: (res) => {
        this.results.set(res);
        this.loading.set(false);
        if (res.hosts.length > 0) {
          this.expandedHosts.set(new Set([res.hosts[0].id]));
        }
      },
      error: () => {
        this.error.set('Failed to load scan results');
        this.loading.set(false);
      },
    });
  }

  toggleHost(id: string): void {
    const set = new Set(this.expandedHosts());
    if (set.has(id)) {
      set.delete(id);
    } else {
      set.add(id);
    }
    this.expandedHosts.set(set);
  }

  togglePort(id: string): void {
    const set = new Set(this.expandedPorts());
    if (set.has(id)) {
      set.delete(id);
    } else {
      set.add(id);
    }
    this.expandedPorts.set(set);
  }

  isHostExpanded(id: string): boolean {
    return this.expandedHosts().has(id);
  }

  isPortExpanded(id: string): boolean {
    return this.expandedPorts().has(id);
  }

  goBack(): void {
    void this.router.navigate(['/history']);
  }

  openPorts(host: HostDto): number {
    return host.ports.filter((p) => p.state === 'open').length;
  }

  totalCves(host: HostDto): number {
    return host.ports.reduce((sum, p) => sum + p.cves.length, 0);
  }

  findingClass(severity: string): string {
    return severity.toLowerCase();
  }

  riskLevelClass(level: string): string {
    return level.toLowerCase();
  }

  expandedFindings = signal<Set<number>>(new Set());

  toggleFinding(index: number): void {
    const set = new Set(this.expandedFindings());
    if (set.has(index)) {
      set.delete(index);
    } else {
      set.add(index);
    }
    this.expandedFindings.set(set);
  }

  isFindingExpanded(index: number): boolean {
    return this.expandedFindings().has(index);
  }

  hasCves(finding: AnalysisFinding): boolean {
    return finding.relatedCves.length > 0;
  }

  cvssClass(score: number | null): string {
    if (score === null) return 'none';
    if (score >= 9.0) return 'critical';
    if (score >= 7.0) return 'high';
    if (score >= 4.0) return 'medium';
    return 'low';
  }

  statusClass(status: string): string {
    return status.toLowerCase();
  }

  durationSeconds(): string {
    const r = this.results();
    if (!r?.completedAt) return '—';
    const diff = Math.round(
      (new Date(r.completedAt).getTime() - new Date(r.startedAt).getTime()) / 1000
    );
    if (diff < 60) return `${diff}s`;
    return `${Math.floor(diff / 60)}m ${diff % 60}s`;
  }
}
