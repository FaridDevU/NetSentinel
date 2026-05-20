import { Component, ElementRef, OnDestroy, OnInit, ViewChild, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { DatePipe } from '@angular/common';
import { Subscription, timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { ScanService } from '../../services/scan.service';
import { LangService } from '../../services/lang.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { AnalysisFinding, FindingStatusMap, HostDto, ScanResultsResponse, WebFindingDto } from '../../models/scan.models';

@Component({
  selector: 'app-results',
  imports: [DatePipe, TranslatePipe],
  templateUrl: './results.html',
  styleUrl: './results.scss',
})
export class ResultsPage implements OnInit, OnDestroy {
  @ViewChild('liveTerminal') liveTerminalRef?: ElementRef<HTMLDivElement>;

  results = signal<ScanResultsResponse | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);
  expandedHosts = signal<Set<string>>(new Set());
  expandedPorts = signal<Set<string>>(new Set());
  expandedFindings = signal<Set<number>>(new Set());
  showTechnical = signal(false);
  scanLogs = signal<string[]>([]);
  findingStatuses = signal<FindingStatusMap>({});

  private scanId = '';
  private logSub?: Subscription;
  private statusSub?: Subscription;

  private lang = inject(LangService);

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private scanService: ScanService
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) { void this.router.navigate(['/history']); return; }
    this.scanId = id;

    this.scanService.getResults(id).subscribe({
      next: (res) => {
        this.results.set(res);
        this.loading.set(false);
        if (res.hosts.length > 0) {
          this.expandedHosts.set(new Set([res.hosts[0].id]));
        }
        if (res.status === 'PENDING' || res.status === 'RUNNING') {
          this.startLivePolling(id);
        }
        this.scanService.getFindingStatuses(id).subscribe({
          next: (s) => this.findingStatuses.set(s),
          error: () => {},
        });
      },
      error: () => {
        this.error.set(this.lang.t('results.error.loadFailed'));
        this.loading.set(false);
      },
    });
  }

  ngOnDestroy(): void {
    this.logSub?.unsubscribe();
    this.statusSub?.unsubscribe();
  }

  private startLivePolling(id: string): void {
    this.logSub = timer(600, 2000)
      .pipe(switchMap(() => this.scanService.getScanLogs(id)))
      .subscribe({
        next: (res) => {
          this.scanLogs.set(res.lines);
          setTimeout(() => {
            if (this.liveTerminalRef?.nativeElement) {
              const el = this.liveTerminalRef.nativeElement;
              el.scrollTop = el.scrollHeight;
            }
          }, 20);
        },
        error: () => {},
      });

    this.statusSub = timer(3000, 3000)
      .pipe(
        switchMap(() => this.scanService.getStatus(id)),
        takeWhile(s => s.status === 'PENDING' || s.status === 'RUNNING', true)
      )
      .subscribe({
        next: (status) => {
          if (status.status !== 'PENDING' && status.status !== 'RUNNING') {
            this.logSub?.unsubscribe();
            this.scanService.getScanLogs(id).subscribe({
              next: (r) => this.scanLogs.set(r.lines),
              error: () => {},
            });
            this.scanService.getResults(id).subscribe({
              next: (res) => {
                this.results.set(res);
                if (res.hosts.length > 0) {
                  this.expandedHosts.set(new Set([res.hosts[0].id]));
                }
              },
              error: () => {},
            });
          }
        },
        error: () => {},
      });
  }

  toggleHost(id: string): void {
    const set = new Set(this.expandedHosts());
    if (set.has(id)) { set.delete(id); } else { set.add(id); }
    this.expandedHosts.set(set);
  }

  togglePort(id: string): void {
    const set = new Set(this.expandedPorts());
    if (set.has(id)) { set.delete(id); } else { set.add(id); }
    this.expandedPorts.set(set);
  }

  toggleFinding(index: number): void {
    const set = new Set(this.expandedFindings());
    if (set.has(index)) { set.delete(index); } else { set.add(index); }
    this.expandedFindings.set(set);
  }

  isHostExpanded(id: string): boolean { return this.expandedHosts().has(id); }
  isPortExpanded(id: string): boolean { return this.expandedPorts().has(id); }
  isFindingExpanded(index: number): boolean { return this.expandedFindings().has(index); }

  goBack(): void { void this.router.navigate(['/history']); }

  downloadExport(format: 'pdf' | 'json' | 'csv'): void {
    const url = this.scanService.exportUrl(this.scanId, format);
    const a = document.createElement('a');
    a.href = url;
    a.download = '';
    a.click();
  }

  buildFindingKey(finding: AnalysisFinding): string {
    if (finding.relatedCves.length > 0) {
      return [...finding.relatedCves].sort().join(',') + '::' + finding.host + '::' + finding.port;
    }
    return finding.severity + '::' + finding.host + '::' + finding.port + '::' + finding.service;
  }

  findingStatus(finding: AnalysisFinding): string {
    return this.findingStatuses()[this.buildFindingKey(finding)] ?? 'OPEN';
  }

  cycleStatus(finding: AnalysisFinding): void {
    const key = this.buildFindingKey(finding);
    const current = this.findingStatuses()[key] ?? 'OPEN';
    const cycle: Record<string, string> = {
      OPEN: 'ACKNOWLEDGED', ACKNOWLEDGED: 'FALSE_POSITIVE', FALSE_POSITIVE: 'RESOLVED', RESOLVED: 'OPEN'
    };
    const next = cycle[current] ?? 'OPEN';
    this.scanService.updateFindingStatus(this.scanId, key, next).subscribe({
      next: () => {
        const updated = { ...this.findingStatuses(), [key]: next };
        this.findingStatuses.set(updated);
      },
      error: () => {},
    });
  }
  openPorts(host: HostDto): number { return host.ports.filter((p) => p.state === 'open').length; }
  totalCves(host: HostDto): number { return host.ports.reduce((sum, p) => sum + p.cves.length, 0); }
  totalWebFindings(host: HostDto): number { return host.webFindings?.length ?? 0; }
  findingClass(severity: string): string { return severity.toLowerCase(); }
  webFindingClass(severity: string): string { return severity.toLowerCase(); }

  cvssClass(score: number | null): string {
    if (score === null) return 'none';
    if (score >= 9.0) return 'critical';
    if (score >= 7.0) return 'high';
    if (score >= 4.0) return 'medium';
    return 'low';
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

  riskBannerClass(): string {
    return (this.results()?.analysis?.riskLevel ?? 'info').toLowerCase();
  }

  riskTitleKey(): string {
    const level = (this.results()?.analysis?.riskLevel ?? 'info').toLowerCase();
    return `results.risk.${level}`;
  }

  riskSubKey(): string {
    const level = (this.results()?.analysis?.riskLevel ?? 'info').toLowerCase();
    return `results.risk.${level}.sub`;
  }

  devicesCount(): number { return this.results()?.hosts.length ?? 0; }
  problemsCount(): number { return this.results()?.analysis?.findings.length ?? 0; }

  deviceLabel(host: { ip: string; hostname: string | null; vendor: string | null }): string {
    if (host.hostname) return host.hostname;
    if (host.vendor) return `${host.vendor} (${host.ip})`;
    return host.ip;
  }

  webFindingToolLabel(tool: string): string {
    return tool === 'gobuster' ? 'Gobuster' : 'Nikto';
  }

  hasWebFindings(hosts: HostDto[]): boolean {
    return hosts.some(h => (h.webFindings?.length ?? 0) > 0);
  }

  allWebFindings(hosts: HostDto[]): { host: string; finding: WebFindingDto }[] {
    return hosts.flatMap(h =>
      (h.webFindings ?? []).map(wf => ({ host: h.ip, finding: wf }))
    );
  }
}
