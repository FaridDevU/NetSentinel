import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class BackendStatusService {
  readonly offline = signal(false);

  markOffline(): void {
    this.offline.set(true);
  }

  markOnline(): void {
    this.offline.set(false);
  }
}
