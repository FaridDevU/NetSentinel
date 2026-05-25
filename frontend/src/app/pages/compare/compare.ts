import { Component, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ScanService } from '../../services/scan.service';
import { UserErrorService } from '../../services/user-error.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { PagedResponse, ScanCompareResponse, ScanStatusResponse } from '../../models/scan.models';

@Component({
  selector: 'app-compare',
  imports: [DatePipe, TranslatePipe],
  templateUrl: './compare.html',
  styleUrl: './compare.scss',
})
export class ComparePage implements OnInit {
  scans = signal<ScanStatusResponse[]>([]);
  scanIdA = signal<string>('');
  scanIdB = signal<string>('');
  result = signal<ScanCompareResponse | null>(null);
  loading = signal(false);
  loadingScans = signal(true);
  error = signal<string | null>(null);

  constructor(
    private scanService: ScanService,
    private userError: UserErrorService
  ) {}

  ngOnInit(): void {
    this.scanService.getHistory(0, 50).subscribe({
      next: (r: PagedResponse<ScanStatusResponse>) => {
        this.scans.set(r.content.filter(s => s.status === 'COMPLETED'));
        this.loadingScans.set(false);
      },
      error: () => this.loadingScans.set(false),
    });
  }

  canCompare(): boolean {
    return !!this.scanIdA() && !!this.scanIdB() && this.scanIdA() !== this.scanIdB();
  }

  compare(): void {
    if (!this.canCompare()) return;
    this.loading.set(true);
    this.result.set(null);
    this.error.set(null);
    this.scanService.compareScans(this.scanIdA(), this.scanIdB()).subscribe({
      next: (r) => { this.result.set(r); this.loading.set(false); },
      error: (err) => { this.error.set(this.userError.message(err, 'compare')); this.loading.set(false); },
    });
  }

  sevClass(sev: string): string { return sev.toLowerCase(); }

  scanLabel(id: string): string {
    const s = this.scans().find(x => x.id === id);
    return s ? s.target : id;
  }
}
