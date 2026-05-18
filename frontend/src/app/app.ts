import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { BackendStatusService } from './services/backend-status.service';
import { LangService } from './services/lang.service';
import { TranslatePipe } from './pipes/translate.pipe';
import { SetupPage } from './pages/setup/setup';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TranslatePipe, SetupPage],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  readonly offline = inject(BackendStatusService).offline;
  readonly lang = inject(LangService);

  checking = signal(true);
  showSetup = signal(false);

  ngOnInit(): void {
    const e = (window as any).electron;
    if (!e?.checkDepsQuick) {
      this.checking.set(false);
      return;
    }
    e.checkDepsQuick()
      .then((r: any) => {
        this.checking.set(false);
        if (!r?.wsl || !r?.kali) {
          this.showSetup.set(true);
        }
      })
      .catch(() => this.checking.set(false));
  }

  openSetup(): void {
    this.showSetup.set(true);
  }
}
