import { Component, EventEmitter, inject, OnDestroy, OnInit, Output, signal } from '@angular/core';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { LangService } from '../../services/lang.service';
import { LucideCheck, LucideX } from '@lucide/angular';

interface DepResult {
  id: string;
  label: string;
  ok: boolean;
  detail: string;
  technicalDetail?: string;
}

type SetupState = 'checking' | 'missing' | 'installing' | 'starting' | 'ready' | 'needs_reboot' | 'error';

@Component({
  selector: 'app-setup',
  imports: [TranslatePipe, LucideCheck, LucideX],
  templateUrl: './setup.html',
  styleUrl: './setup.scss',
})
export class SetupPage implements OnInit, OnDestroy {
  @Output() done = new EventEmitter<void>();

  readonly lang = inject(LangService);

  private static readonly UAC_TIMEOUT_MS = 25000;

  state = signal<SetupState>('checking');
  deps = signal<DepResult[]>([]);
  statusMsg = signal('');
  errorBodyKey = signal('setup.error.body');
  setupDetail = signal('');
  showTechnicalDetails = signal(false);
  nvdKey = '';
  nvdSaved = signal(false);

  private pollTimer?: ReturnType<typeof setInterval>;
  private installStartedAt = 0;
  private sawProgress = false;
  private engineRetries = 0;

  private get electron(): any {
    return (window as any).electron;
  }

  ngOnInit(): void {
    this.runCheck();
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  private runCheck(): void {
    this.state.set('checking');
    this.electron.checkDeps().then((results: DepResult[]) => {
      this.deps.set(results);
      if (results.length > 0 && results.every((d) => d.ok)) {
        this.engineRetries = 0;
        this.state.set('ready');
        return;
      }
      const infra = results.filter((d) => d.id !== 'api');
      const apiDep = results.find((d) => d.id === 'api');
      const infraInstalled = infra.length > 0 && infra.every((d) => d.ok);
      if (infraInstalled && apiDep && !apiDep.ok) {
        this.startEngine();
      } else {
        this.engineRetries = 0;
        this.state.set('missing');
      }
    }).catch(() => {
      this.state.set('missing');
    });
  }

  private startEngine(): void {
    this.state.set('starting');
    if (!this.electron?.startBackend) {
      this.state.set('missing');
      return;
    }
    this.electron.startBackend().then((ok: boolean) => {
      if (ok) {
        this.engineRetries = 0;
        this.runCheck();
      } else if (this.engineRetries < 2) {
        this.engineRetries++;
        this.startEngine();
      } else {
        this.engineRetries = 0;
        this.state.set('missing');
      }
    }).catch(() => {
      if (this.engineRetries < 2) {
        this.engineRetries++;
        this.startEngine();
      } else {
        this.engineRetries = 0;
        this.state.set('missing');
      }
    });
  }

  install(): void {
    this.statusMsg.set('');
    this.setupDetail.set('');
    this.showTechnicalDetails.set(false);
    this.errorBodyKey.set('setup.error.body');
    this.installStartedAt = Date.now();
    this.sawProgress = false;
    this.state.set('installing');
    this.electron.runSetup();
    this.pollTimer = setInterval(() => this.pollStatus(), 3000);
    this.pollStatus();
  }

  private pollStatus(): void {
    this.refreshSetupDetail();
    this.electron.getSetupStatus().then((status: string) => {
      const progress: Record<string, string> = {
        'INSTALLING_TOOLS': 'setup.progress.tools',
        'CONFIGURING_DB': 'setup.progress.db',
        'INSTALLING_BACKEND': 'setup.progress.backend',
        'INSTALLING_SANDBOX': 'setup.progress.sandbox',
        'CREATING_SCRIPTS': 'setup.progress.scripts',
      };
      if (progress[status]) {
        this.sawProgress = true;
        this.statusMsg.set(progress[status]);
      } else if (status === 'READY') {
        this.sawProgress = true;
        this.stopPolling();
        this.electron.startBackend().then(() => this.runCheck()).catch(() => this.runCheck());
      } else if (status === 'NEEDS_REBOOT') {
        this.sawProgress = true;
        this.stopPolling();
        this.state.set('needs_reboot');
      } else if (status.endsWith('_FAILED') || status === 'SETUP_FAILED') {
        this.sawProgress = true;
        this.stopPolling();
        this.errorBodyKey.set('setup.error.body');
        this.state.set('error');
      } else if (!this.sawProgress && Date.now() - this.installStartedAt > SetupPage.UAC_TIMEOUT_MS) {
        this.stopPolling();
        this.errorBodyKey.set('setup.error.uac');
        this.state.set('error');
      }
    }).catch(() => {});
  }

  verifyEngine(): void {
    this.engineRetries = 0;
    this.runCheck();
  }

  verifyNow(): void {
    this.electron.checkDeps().then((results: DepResult[]) => {
      this.deps.set(results);
      if (results.length > 0 && results.every((d) => d.ok)) {
        this.stopPolling();
        this.state.set('ready');
      } else {
        this.pollStatus();
      }
    }).catch(() => this.pollStatus());
  }

  reboot(): void {
    this.electron.reboot();
  }

  retry(): void {
    this.statusMsg.set('');
    this.setupDetail.set('');
    this.showTechnicalDetails.set(false);
    this.runCheck();
  }

  toggleTechnicalDetails(): void {
    this.showTechnicalDetails.update((v) => !v);
    this.refreshSetupDetail();
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = undefined;
    }
  }

  saveNvdKey(): void {
    const key = this.nvdKey.trim();
    if (!key) return;
    this.electron.saveNvdKey(key).then(() => {
      this.nvdSaved.set(true);
    }).catch(() => {});
  }

  private refreshSetupDetail(): void {
    if (!this.electron?.getSetupDetail) return;
    this.electron.getSetupDetail().then((detail: string) => {
      this.setupDetail.set(detail ?? '');
    }).catch(() => {});
  }

  closeApp(): void {
    (window as any).electron?.closeWindow?.();
  }

  skip(): void {
    this.done.emit();
  }

  continue(): void {
    localStorage.setItem('ns_welcomed', '1');
    this.done.emit();
  }
}
