import { Component, ElementRef, OnDestroy, ViewChild, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Subscription, timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { ScanService } from '../../services/scan.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { LocalNetworkInterface, ScanStatus } from '../../models/scan.models';

type ScanProfile = 'quick' | 'full' | 'custom';

interface Profile {
  key: ScanProfile;
  labelKey: string;
  descKey: string;
  flags: string[];
}

@Component({
  selector: 'app-scan',
  imports: [FormsModule, TranslatePipe],
  templateUrl: './scan.html',
  styleUrl: './scan.scss',
})
export class ScanPage implements OnDestroy {
  @ViewChild('terminal') terminalRef?: ElementRef<HTMLDivElement>;

  target = '';
  selectedProfile: ScanProfile = 'quick';
  customFlags = '';
  targetTouched = false;

  localNetworks = signal<LocalNetworkInterface[]>([]);
  loadingNetworks = signal(false);
  showNetworkPicker = signal(false);

  scanning = signal(false);
  currentScanId = signal<string | null>(null);
  currentStatus = signal<ScanStatus | null>(null);
  elapsedSeconds = signal(0);
  errorMessage = signal<string | null>(null);
  scanLogs = signal<string[]>([]);

  private static readonly TARGET_REGEX = /^[a-zA-Z0-9.\-:/\[\]]{1,100}$/;

  get targetError(): string | null {
    if (!this.targetTouched || !this.target.trim()) return null;
    if (!ScanPage.TARGET_REGEX.test(this.target.trim())) {
      return 'Only letters, numbers, dots, hyphens, colons, slashes, and brackets allowed';
    }
    return null;
  }

  get targetValid(): boolean {
    const t = this.target.trim();
    return t.length > 0 && ScanPage.TARGET_REGEX.test(t);
  }

  private pollSub?: Subscription;
  private timerSub?: Subscription;
  private logSub?: Subscription;

  readonly profiles: Profile[] = [
    { key: 'quick',  labelKey: 'profile.quick.label',  descKey: 'profile.quick.desc',  flags: ['-sV', '-T4'] },
    { key: 'full',   labelKey: 'profile.deep.label',   descKey: 'profile.deep.desc',   flags: ['-sV', '-T4', '-A'] },
    { key: 'custom', labelKey: 'profile.custom.label', descKey: 'profile.custom.desc', flags: [] },
  ];

  private scanService = inject(ScanService);
  private router = inject(Router);

  constructor() {
    effect(() => {
      const logs = this.scanLogs();
      if (logs.length > 0) {
        setTimeout(() => {
          if (this.terminalRef?.nativeElement) {
            const el = this.terminalRef.nativeElement;
            el.scrollTop = el.scrollHeight;
          }
        }, 20);
      }
    });
  }

  detectLocalNetworks(): void {
    if (this.loadingNetworks()) return;
    this.loadingNetworks.set(true);
    this.showNetworkPicker.set(false);
    this.scanService.getLocalNetworks().subscribe({
      next: (nets) => {
        this.localNetworks.set(nets);
        this.loadingNetworks.set(false);
        this.showNetworkPicker.set(nets.length > 0);
      },
      error: () => this.loadingNetworks.set(false),
    });
  }

  selectNetwork(net: LocalNetworkInterface): void {
    this.target = net.subnet;
    this.targetTouched = true;
    this.showNetworkPicker.set(false);
  }

  selectProfile(key: ScanProfile): void {
    this.selectedProfile = key;
  }

  private getParameters(): string[] {
    if (this.selectedProfile === 'custom') {
      return this.customFlags.trim().split(/\s+/).filter((f) => f.length > 0);
    }
    return this.profiles.find((p) => p.key === this.selectedProfile)!.flags;
  }

  startScan(): void {
    const target = this.target.trim();
    if (!target) return;

    this.errorMessage.set(null);
    this.scanLogs.set([]);
    this.scanning.set(true);
    this.elapsedSeconds.set(0);
    this.currentStatus.set('PENDING');

    this.timerSub = timer(1000, 1000).subscribe(() => {
      this.elapsedSeconds.update((s) => s + 1);
    });

    this.scanService.startScan(target, this.getParameters()).subscribe({
      next: (res) => {
        this.currentScanId.set(res.id);
        this.currentStatus.set(res.status);
        this.startPolling(res.id);
        this.startLogPolling(res.id);
      },
      error: (err) => {
        this.stopTimers();
        this.scanning.set(false);
        this.currentStatus.set('FAILED');
        this.errorMessage.set(err?.error?.error ?? 'Failed to start scan');
      },
    });
  }

  private startLogPolling(id: string): void {
    this.logSub = timer(800, 2000)
      .pipe(switchMap(() => this.scanService.getScanLogs(id)))
      .subscribe({
        next: (res) => this.scanLogs.set(res.lines),
        error: () => {},
      });
  }

  private startPolling(id: string): void {
    this.pollSub = timer(0, 2000)
      .pipe(
        switchMap(() => this.scanService.getStatus(id)),
        takeWhile((s) => s.status === 'PENDING' || s.status === 'RUNNING', true)
      )
      .subscribe({
        next: (status) => {
          this.currentStatus.set(status.status);
          if (status.status === 'COMPLETED') {
            this.logSub?.unsubscribe();
            this.scanService.getScanLogs(id).subscribe({
              next: (res) => this.scanLogs.set(res.lines),
              error: () => {},
            });
            this.stopTimers();
            this.router.navigate(['/results', id]);
          } else if (status.status === 'FAILED' || status.status === 'CANCELLED') {
            this.stopTimers();
            this.scanning.set(false);
            this.errorMessage.set(status.errorMessage ?? 'Scan did not complete successfully');
          }
        },
        error: () => {
          this.stopTimers();
          this.scanning.set(false);
          this.errorMessage.set('Lost connection to backend');
        },
      });
  }

  cancelScan(): void {
    const id = this.currentScanId();
    if (!id) return;
    this.scanService.cancelScan(id).subscribe({
      next: () => {
        this.stopTimers();
        this.scanning.set(false);
        this.currentStatus.set('CANCELLED');
      },
      error: () => {
        this.stopTimers();
        this.scanning.set(false);
      },
    });
  }

  resetForm(): void {
    this.scanning.set(false);
    this.currentScanId.set(null);
    this.currentStatus.set(null);
    this.elapsedSeconds.set(0);
    this.errorMessage.set(null);
    this.scanLogs.set([]);
    this.stopTimers();
  }

  private stopTimers(): void {
    this.pollSub?.unsubscribe();
    this.timerSub?.unsubscribe();
    this.logSub?.unsubscribe();
  }

  ngOnDestroy(): void {
    this.stopTimers();
  }

  indicatorClass(): string {
    return this.currentStatus()?.toLowerCase() ?? '';
  }

  formatElapsed(seconds: number): string {
    if (seconds < 60) return `${seconds}s`;
    return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  }
}
