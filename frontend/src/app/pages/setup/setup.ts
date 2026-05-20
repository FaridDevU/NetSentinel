import { Component, EventEmitter, OnDestroy, OnInit, Output, signal } from '@angular/core';
import { TranslatePipe } from '../../pipes/translate.pipe';

interface DepResult {
  id: string;
  label: string;
  ok: boolean;
  detail: string;
}

type SetupState = 'checking' | 'missing' | 'installing' | 'ready' | 'needs_reboot' | 'error';

@Component({
  selector: 'app-setup',
  imports: [TranslatePipe],
  templateUrl: './setup.html',
  styleUrl: './setup.scss',
})
export class SetupPage implements OnInit, OnDestroy {
  @Output() done = new EventEmitter<void>();

  state = signal<SetupState>('checking');
  deps = signal<DepResult[]>([]);
  statusMsg = signal('');
  nvdKey = '';
  nvdSaved = signal(false);

  private pollTimer?: ReturnType<typeof setInterval>;

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
      const allOk = results.length > 0 && results.every((d) => d.ok);
      this.state.set(allOk ? 'ready' : 'missing');
    }).catch(() => {
      this.state.set('missing');
    });
  }

  install(): void {
    this.statusMsg.set('');
    this.state.set('installing');
    this.electron.runSetup();
    this.pollTimer = setInterval(() => this.pollStatus(), 3000);
  }

  private pollStatus(): void {
    this.electron.getSetupStatus().then((status: string) => {
      const progress: Record<string, string> = {
        'INSTALLING_TOOLS': 'setup.progress.tools',
        'CONFIGURING_DB': 'setup.progress.db',
        'INSTALLING_BACKEND': 'setup.progress.backend',
        'INSTALLING_SANDBOX': 'setup.progress.sandbox',
        'CREATING_SCRIPTS': 'setup.progress.scripts',
      };
      if (progress[status]) {
        this.statusMsg.set(progress[status]);
      } else if (status === 'READY') {
        this.stopPolling();
        this.electron.startBackend();
        this.runCheck();
      } else if (status === 'NEEDS_REBOOT') {
        this.stopPolling();
        this.state.set('needs_reboot');
      } else if (status === 'KALI_INSTALL_FAILED') {
        this.stopPolling();
        this.state.set('error');
      }
    }).catch(() => {});
  }

  verifyNow(): void {
    this.pollStatus();
  }

  reboot(): void {
    this.electron.reboot();
  }

  retry(): void {
    this.statusMsg.set('');
    this.runCheck();
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

  skip(): void {
    this.done.emit();
  }

  continue(): void {
    this.done.emit();
  }
}
