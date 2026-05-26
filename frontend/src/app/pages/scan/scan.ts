import { Component, ElementRef, OnDestroy, OnInit, ViewChild, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Subscription, timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { ScanService } from '../../services/scan.service';
import { LangService } from '../../services/lang.service';
import { UserErrorService } from '../../services/user-error.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { LocalNetworkInterface, ScanProfile, ScanStatus } from '../../models/scan.models';
import { LucideChevronDown, LucideRouter, LucideShield } from '@lucide/angular';

@Component({
  selector: 'app-scan',
  imports: [FormsModule, TranslatePipe, LucideChevronDown, LucideRouter, LucideShield],
  templateUrl: './scan.html',
  styleUrl: './scan.scss',
})
export class ScanPage implements OnInit, OnDestroy {
  @ViewChild('terminal') terminalRef?: ElementRef<HTMLDivElement>;

  target = '';
  targetTouched = false;

  localNetworks = signal<LocalNetworkInterface[]>([]);
  loadingNetworks = signal(true);
  detectedNetwork = signal<LocalNetworkInterface | null>(null);
  showNetworkPicker = signal(false);
  showManualInput = signal(false);

  scanning = signal(false);
  currentScanId = signal<string | null>(null);
  currentStatus = signal<ScanStatus | null>(null);
  elapsedSeconds = signal(0);
  errorMessage = signal<string | null>(null);
  scanLogs = signal<string[]>([]);
  showTerminal = signal(false);
  scanProfile = signal<ScanProfile>('ESTANDAR');

  private static readonly TARGET_REGEX = /^[a-zA-Z0-9][a-zA-Z0-9.\-:/\[\]]{0,99}$/;
  private static readonly PROFILE_PARAMS: Record<ScanProfile, string[]> = {
    RAPIDO:   ['-sV', '-T4', '--top-ports', '100'],
    ESTANDAR: ['-sV', '-T4'],
    COMPLETO: ['-sV', '-T4', '-p-'],
  };

  get targetError(): string | null {
    if (!this.targetTouched || !this.target.trim()) return null;
    if (!ScanPage.TARGET_REGEX.test(this.target.trim())) {
      return this.lang.t('scan.error.invalidTarget');
    }
    return null;
  }

  get targetValid(): boolean {
    const t = this.target.trim();
    return t.length > 0 && ScanPage.TARGET_REGEX.test(t);
  }

  get canScan(): boolean {
    return !this.loadingNetworks() && (this.detectedNetwork() !== null || this.targetValid);
  }

  progressStep = computed<string>(() => {
    const logs = this.scanLogs();
    if (!logs.length) return 'scan.step.searching';
    const last = logs[logs.length - 1].toLowerCase();
    if (last.includes('listo') || last.includes('calculando')) {
      return 'scan.step.analyzing';
    }
    if (last.includes('web') || last.includes('gobuster') || last.includes('nikto')) {
      return 'scan.step.web';
    }
    if (last.includes('cve') || last.includes('consultando')) {
      return 'scan.step.checking';
    }
    if (last.includes('nmap finalizado') || last.includes('dispositivo')) {
      return 'scan.step.found';
    }
    return 'scan.step.searching';
  });

  private pollSub?: Subscription;
  private timerSub?: Subscription;
  private logSub?: Subscription;

  private scanService = inject(ScanService);
  private lang = inject(LangService);
  private userError = inject(UserErrorService);
  private router = inject(Router);

  constructor() {
    effect(() => {
      const logs = this.scanLogs();
      if (logs.length > 0 && this.showTerminal()) {
        setTimeout(() => {
          if (this.terminalRef?.nativeElement) {
            const el = this.terminalRef.nativeElement;
            el.scrollTop = el.scrollHeight;
          }
        }, 20);
      }
    });
  }

  ngOnInit(): void {
    this.autoDetect();
  }

  private autoDetect(): void {
    this.loadingNetworks.set(true);
    this.scanService.getLocalNetworks().subscribe({
      next: (nets) => {
        this.localNetworks.set(nets);
        this.loadingNetworks.set(false);
        if (nets.length === 1) {
          this.detectedNetwork.set(nets[0]);
          this.target = nets[0].subnet;
        } else if (nets.length > 1) {
          this.showNetworkPicker.set(true);
        } else {
          this.showManualInput.set(true);
        }
      },
      error: () => {
        this.loadingNetworks.set(false);
        this.showManualInput.set(true);
      },
    });
  }

  selectNetwork(net: LocalNetworkInterface): void {
    this.detectedNetwork.set(net);
    this.target = net.subnet;
    this.showNetworkPicker.set(false);
    this.showManualInput.set(false);
  }

  changeNetwork(): void {
    this.detectedNetwork.set(null);
    this.target = '';
    if (this.localNetworks().length > 1) {
      this.showNetworkPicker.set(true);
    } else {
      this.showManualInput.set(true);
    }
  }

  showManual(): void {
    this.showManualInput.set(true);
    this.showNetworkPicker.set(false);
  }

  retryDetect(): void {
    this.detectedNetwork.set(null);
    this.showNetworkPicker.set(false);
    this.showManualInput.set(false);
    this.target = '';
    this.autoDetect();
  }

  startScan(): void {
    const target = this.target.trim();
    if (!target) return;

    this.errorMessage.set(null);
    this.scanLogs.set([]);
    this.scanning.set(true);
    this.elapsedSeconds.set(0);
    this.currentStatus.set('PENDING');
    this.showTerminal.set(false);

    this.timerSub = timer(1000, 1000).subscribe(() => {
      this.elapsedSeconds.update((s) => s + 1);
    });

    this.scanService.startScan(target, ScanPage.PROFILE_PARAMS[this.scanProfile()]).subscribe({
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
        this.errorMessage.set(this.userError.message(err, 'scan-start'));
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
            void this.router.navigate(['/results', id]);
          } else if (status.status === 'FAILED' || status.status === 'CANCELLED') {
            this.stopTimers();
            this.scanning.set(false);
            this.errorMessage.set(this.userError.scanFailure(status.errorMessage));
          }
        },
        error: (err) => {
          this.stopTimers();
          this.scanning.set(false);
          this.errorMessage.set(this.userError.message(err, 'scan-poll'));
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
    this.showTerminal.set(false);
    this.stopTimers();
    this.autoDetect();
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
