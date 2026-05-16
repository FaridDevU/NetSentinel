import { Component, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { ScanService } from '../../services/scan.service';
import { PagedResponse, ScanStatusResponse } from '../../models/scan.models';

@Component({
  selector: 'app-history',
  imports: [DatePipe, RouterLink],
  templateUrl: './history.html',
  styleUrl: './history.scss',
})
export class HistoryPage implements OnInit {
  data = signal<PagedResponse<ScanStatusResponse> | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);
  currentPage = signal(0);
  deletingId = signal<string | null>(null);

  constructor(
    private scanService: ScanService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.load(0);
  }

  load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.scanService.getHistory(page).subscribe({
      next: (res) => {
        this.data.set(res);
        this.currentPage.set(page);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Failed to load history. Is the backend running?');
        this.loading.set(false);
      },
    });
  }

  goToPage(page: number): void {
    this.load(page);
  }

  openResults(id: string): void {
    this.router.navigate(['/results', id]);
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

  durationSeconds(scan: ScanStatusResponse): string {
    if (!scan.completedAt) return '—';
    const diff = Math.round(
      (new Date(scan.completedAt).getTime() - new Date(scan.startedAt).getTime()) / 1000
    );
    if (diff < 60) return `${diff}s`;
    return `${Math.floor(diff / 60)}m ${diff % 60}s`;
  }
}
