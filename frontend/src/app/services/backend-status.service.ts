import { Injectable, computed, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class BackendStatusService {
  readonly repairing = signal(false);
  readonly offline = computed(() => this.repairing());
  private recoverTimer?: ReturnType<typeof setInterval>;
  private recoveryStarted = false;

  markOffline(): void {
    this.repairing.set(true);
    this.startRecovery();
  }

  markOnline(): void {
    this.repairing.set(false);
    this.recoveryStarted = false;
    this.stopRecovery();
  }

  private startRecovery(): void {
    this.tryStartBackend();
    void this.checkHealth();
    if (this.recoverTimer) return;
    this.recoverTimer = setInterval(() => {
      void this.checkHealth();
    }, 5000);
  }

  private tryStartBackend(): void {
    if (this.recoveryStarted) return;
    this.recoveryStarted = true;
    const e = (window as any).electron;
    if (e?.startBackend) {
      void e.startBackend().catch(() => {});
    }
  }

  private async checkHealth(): Promise<void> {
    try {
      const response = await fetch('http://localhost:8080/api/health');
      if (response.ok) this.markOnline();
    } catch {}
  }

  private stopRecovery(): void {
    if (this.recoverTimer) {
      clearInterval(this.recoverTimer);
      this.recoverTimer = undefined;
    }
  }
}
