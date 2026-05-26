import { Component, inject, OnInit, signal } from '@angular/core';
import { NgStyle } from '@angular/common';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { BackendStatusService } from './services/backend-status.service';
import { LangService } from './services/lang.service';
import { TutorialService } from './services/tutorial.service';
import { TranslatePipe } from './pipes/translate.pipe';
import { SetupPage } from './pages/setup/setup';
import {
  LucideChartNoAxesColumn,
  LucideCircleAlert,
  LucideCircleHelp,
  LucideHistory,
  LucideLayoutDashboard,
  LucideMessageCircle,
  LucideMinus,
  LucideSearch,
  LucideServer,
  LucideUser,
  LucideX,
} from '@lucide/angular';

interface TourArrowLine {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

@Component({
  selector: 'app-root',
  imports: [
    NgStyle,
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    TranslatePipe,
    SetupPage,
    LucideChartNoAxesColumn,
    LucideCircleAlert,
    LucideCircleHelp,
    LucideHistory,
    LucideLayoutDashboard,
    LucideMessageCircle,
    LucideMinus,
    LucideSearch,
    LucideServer,
    LucideUser,
    LucideX,
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  readonly offline = inject(BackendStatusService).offline;
  readonly lang = inject(LangService);
  readonly tutorial = inject(TutorialService);

  checking = signal(true);
  showSetup = signal(false);

  ngOnInit(): void {
    const e = (window as any).electron;
    if (!e?.checkDepsQuick) {
      this.checking.set(false);
      this.expandWindow();
      return;
    }
    e.checkDepsQuick()
      .then((r: any) => {
        this.checking.set(false);
        const welcomed = localStorage.getItem('ns_welcomed') === '1';
        if (!r?.wsl || !r?.kali || !welcomed) {
          this.showSetup.set(true);
        } else {
          this.expandWindow();
        }
      })
      .catch(() => {
        this.checking.set(false);
        this.showSetup.set(true);
      });
  }

  onSetupDone(): void {
    this.expandWindow();
    this.showSetup.set(false);
  }

  openSetup(): void {
    this.showSetup.set(true);
  }

  minimize(): void {
    (window as any).electron?.minimizeWindow?.();
  }

  closeApp(): void {
    (window as any).electron?.closeWindow?.();
  }

  spotlightStyle(): Record<string, string> {
    const rect = this.tutorial.spotlight();
    if (!rect) return {};
    return {
      top: `${rect.top}px`,
      left: `${rect.left}px`,
      width: `${rect.width}px`,
      height: `${rect.height}px`,
    };
  }

  arrowLine(): TourArrowLine | null {
    const rect = this.tutorial.spotlight();
    if (!rect) return null;
    return {
      x1: window.innerWidth - 330,
      y1: window.innerHeight - 178,
      x2: rect.left + rect.width / 2,
      y2: rect.top + rect.height / 2,
    };
  }

  private expandWindow(): void {
    (window as any).electron?.expandWindow?.();
  }
}
