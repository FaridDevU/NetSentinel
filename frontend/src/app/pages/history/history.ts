import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { Subscription, timer } from 'rxjs';
import { ScanService } from '../../services/scan.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { PagedResponse, ScanStatusResponse } from '../../models/scan.models';

@Component({
  selector: 'app-history',
  imports: [DatePipe, RouterLink, TranslatePipe],
  templateUrl: './history.html',
  styleUrl: './history.scss',
})
export class HistoryPage implements OnInit, OnDestroy {
  data = signal<PagedResponse<ScanStatusResponse> | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);
  currentPage = signal(0);
  deletingId = signal<string | null>(null);

  private refreshSub?: Subscription;

  constructor(
    private scanService: ScanService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.load(0);
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.scanService.getHistory(page).subscribe({
      next: (res) => {
        this.data.set(res);
        this.currentPage.set(page);
        this.loading.set(false);
        this.scheduleRefreshIfNeeded();
      },
      error: () => {
        this.error.set('Failed to load history. Is the backend running?');
        this.loading.set(false);
      },
    });
  }

  private scheduleRefreshIfNeeded(): void {
    this.refreshSub?.unsubscribe();
    const d = this.data();
    if (!d) return;
    const hasActive = d.content.some(
      (s) => s.status === 'PENDING' || s.status === 'RUNNING'
    );
    if (hasActive) {
      this.refreshSub = timer(4000).subscribe(() => {
        this.load(this.currentPage());
      });
    }
  }

  goToPage(page: number): void {
    this.load(page);
  }

  openResults(id: string): void {
    void this.router.navigate(['/results', id]);
  }

  deleteScan(event: Event, id: string): void {
    event.stopPropagation();
    this.deletingId.set(id);
    this.scanService.deleteScan(id).subscribe({
      next: () => {
        this.deletingId.set(null);
        this.load(this.currentPage());
      },
      error: () => {
        this.deletingId.set(null);
      },
    });
  }

  pages(): number[] {
    const total = this.data()?.totalPages ?? 0;
    return Array.from({ length: total }, (_, i) => i);
  }

  statusClass(status: string): string {
    return status.toLowerCase();
  }

  isActive(status: string): boolean {
    return status === 'PENDING' || status === 'RUNNING';
  }

  durationSeconds(scan: ScanStatusResponse): string {
    if (!scan.completedAt) return '—';
    const diff = Math.round(
      (new Date(scan.completedAt).getTime() - new Date(scan.startedAt).getTime()) / 1000
    );
    if (diff < 60) return `${diff}s`;
    return `${Math.floor(diff / 60)}m ${diff % 60}s`;
  }
}
