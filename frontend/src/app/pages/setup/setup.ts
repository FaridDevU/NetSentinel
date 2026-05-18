import { Component, EventEmitter, OnDestroy, OnInit, Output, signal } from '@angular/core';
import { TranslatePipe } from '../../pipes/translate.pipe';

interface DepResult {
  id: string;
  label: string;
  ok: boolean;
  detail: string;
}

type SetupState = 'checking' | 'missing' | 'installing' | 'ready';

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
    this.state.set('installing');
    this.electron.runSetup();
    this.pollTimer = setInterval(() => this.pollStatus(), 3000);
  }

  private pollStatus(): void {
    this.electron.getSetupStatus().then((status: string) => {
      if (status === 'READY') {
        this.stopPolling();
        this.electron.startBackend();
        this.runCheck();
      }
    }).catch(() => {});
  }

  verifyNow(): void {
    this.pollStatus();
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = undefined;
    }
  }

  skip(): void {
    this.done.emit();
  }

  continue(): void {
    this.done.emit();
  }
}
