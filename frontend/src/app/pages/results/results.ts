import { Component, ElementRef, OnDestroy, OnInit, ViewChild, computed, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { Subscription, timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { ScanService } from '../../services/scan.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { SettingsPage } from '../settings/settings';
import { AnalysisFinding, HostDto, ScanResultsResponse } from '../../models/scan.models';

export interface ReportItem { type: 'p' | 'li' | 'h4'; text: string; }
export interface ReportSection { title: string; items: ReportItem[]; }

@Component({
  selector: 'app-results',
  imports: [DatePipe, RouterLink, TranslatePipe],
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
  scanLogs = signal<string[]>([]);

  aiReport = signal<string | null>(null);
  generatingAi = signal(false);
  aiError = signal<string | null>(null);

  parsedReport = computed<ReportSection[]>(() => {
    const raw = this.aiReport();
    return raw ? this.parseReport(raw) : [];
  });

  private scanId = '';
  private logSub?: Subscription;
  private statusSub?: Subscription;

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
      },
      error: () => {
        this.error.set('Failed to load scan results');
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
            // Fetch final logs once
            this.scanService.getScanLogs(id).subscribe({
              next: (r) => this.scanLogs.set(r.lines),
              error: () => {},
            });
            // Reload full results
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

  get hasApiKey(): boolean {
    return (SettingsPage.getStoredKey() ?? '').length > 0;
  }

  generateAiReport(): void {
    const apiKey = SettingsPage.getStoredKey();
    if (!apiKey) return;

    this.generatingAi.set(true);
    this.aiError.set(null);
    this.aiReport.set(null);

    this.scanService.generateAiReport(this.scanId, apiKey).subscribe({
      next: (res) => {
        this.aiReport.set(res.report);
        this.generatingAi.set(false);
        const r = this.results();
        if (r) {
          localStorage.setItem(`ns_report_${this.scanId}`, JSON.stringify({
            scanId: this.scanId,
            target: r.target,
            date: r.completedAt ?? r.startedAt,
            report: res.report,
            riskLevel: r.analysis?.riskLevel,
          }));
        }
      },
      error: (err) => {
        this.generatingAi.set(false);
        this.aiError.set(err?.error?.error ?? 'AI analysis failed');
      },
    });
  }

  private parseReport(text: string): ReportSection[] {
    const sections: ReportSection[] = [];
    let current: ReportSection | null = null;
    for (const line of text.split('\n')) {
      const t = line.trim();
      if (t.startsWith('## ')) {
        if (current) sections.push(current);
        current = { title: this.stripMd(t.slice(3)), items: [] };
      } else if (t.startsWith('### ')) {
        current?.items.push({ type: 'h4', text: this.stripMd(t.slice(4)) });
      } else if (/^[-*] /.test(t)) {
        current?.items.push({ type: 'li', text: this.stripMd(t.slice(2)) });
      } else if (/^\d+\. /.test(t)) {
        current?.items.push({ type: 'li', text: this.stripMd(t.replace(/^\d+\. /, '')) });
      } else if (t && t !== '---' && t !== '***' && !t.startsWith('# ')) {
        if (!current) current = { title: '', items: [] };
        current.items.push({ type: 'p', text: this.stripMd(t) });
      }
    }
    if (current) sections.push(current);
    return sections;
  }

  private stripMd(text: string): string {
    return text.replace(/\*\*(.*?)\*\*/g, '$1').replace(/\*(.*?)\*/g, '$1').replace(/`(.*?)`/g, '$1');
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

  isHostExpanded(id: string): boolean { return this.expandedHosts().has(id); }
  isPortExpanded(id: string): boolean { return this.expandedPorts().has(id); }
  goBack(): void { void this.router.navigate(['/history']); }
  openPorts(host: HostDto): number { return host.ports.filter((p) => p.state === 'open').length; }
  totalCves(host: HostDto): number { return host.ports.reduce((sum, p) => sum + p.cves.length, 0); }
  findingClass(severity: string): string { return severity.toLowerCase(); }
  riskLevelClass(level: string): string { return level.toLowerCase(); }

  expandedFindings = signal<Set<number>>(new Set());
  showTechnical = signal(false);

  toggleFinding(index: number): void {
    const set = new Set(this.expandedFindings());
    if (set.has(index)) { set.delete(index); } else { set.add(index); }
    this.expandedFindings.set(set);
  }
  isFindingExpanded(index: number): boolean { return this.expandedFindings().has(index); }
  hasCves(finding: AnalysisFinding): boolean { return finding.relatedCves.length > 0; }

  cvssClass(score: number | null): string {
    if (score === null) return 'none';
    if (score >= 9.0) return 'critical';
    if (score >= 7.0) return 'high';
    if (score >= 4.0) return 'medium';
    return 'low';
  }

  statusClass(status: string): string { return status.toLowerCase(); }

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

  devicesCount(): number {
    return this.results()?.hosts.length ?? 0;
  }

  problemsCount(): number {
    return this.results()?.analysis?.findings.length ?? 0;
  }

  deviceLabel(host: { ip: string; hostname: string | null; vendor: string | null }): string {
    if (host.hostname) return host.hostname;
    if (host.vendor) return `${host.vendor} (${host.ip})`;
    return host.ip;
  }

  friendlySeverityKey(severity: string): string {
    return `severity.${severity.toLowerCase()}`;
  }
}
