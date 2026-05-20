import { Component, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { ScanService } from '../../services/scan.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { AssetDto } from '../../models/scan.models';

@Component({
  selector: 'app-assets',
  imports: [DatePipe, TranslatePipe],
  templateUrl: './assets.html',
  styleUrl: './assets.scss',
})
export class AssetsPage implements OnInit {
  assets = signal<AssetDto[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);
  filterRisk = signal<string>('');

  constructor(private scanService: ScanService, private router: Router) {}

  ngOnInit(): void {
    this.scanService.getAssets().subscribe({
      next: (a) => { this.assets.set(a); this.loading.set(false); },
      error: () => { this.error.set('assets.error'); this.loading.set(false); },
    });
  }

  filtered(): AssetDto[] {
    const f = this.filterRisk();
    if (!f) return this.assets();
    return this.assets().filter(a => a.riskLevel === f);
  }

  openResults(scanId: string | null): void {
    if (scanId) void this.router.navigate(['/results', scanId]);
  }

  riskClass(level: string): string {
    return level.toLowerCase();
  }

  severityKey(level: string): string {
    return `severity.${level.toLowerCase()}`;
  }
}
