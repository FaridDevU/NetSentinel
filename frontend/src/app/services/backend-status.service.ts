import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class BackendStatusService {
  readonly offline = signal(false);
  private recoverTimer?: ReturnType<typeof setInterval>;

  markOffline(): void {
    this.offline.set(true);
    this.startRecovery();
  }

  markOnline(): void {
    this.offline.set(false);
    this.stopRecovery();
  }

  private startRecovery(): void {
    if (this.recoverTimer) return;
    this.recoverTimer = setInterval(() => {
      fetch('http://localhost:8080/api/health')
        .then(r => { if (r.ok) this.markOnline(); })
        .catch(() => {});
    }, 5000);
  }

  private stopRecovery(): void {
    if (this.recoverTimer) {
      clearInterval(this.recoverTimer);
      this.recoverTimer = undefined;
    }
  }
}
