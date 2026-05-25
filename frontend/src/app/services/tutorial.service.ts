import { Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';

interface TutorialStep {
  selector: string;
  route?: string;
  title: string;
  body: string;
}

interface SpotlightRect {
  top: number;
  left: number;
  width: number;
  height: number;
}

@Injectable({ providedIn: 'root' })
export class TutorialService {
  readonly active = signal(false);
  readonly index = signal(0);
  readonly spotlight = signal<SpotlightRect | null>(null);
  readonly missingTarget = signal(false);

  readonly steps: TutorialStep[] = [
    {
      selector: '[data-tour="nav-scan"]',
      route: '/scan',
      title: 'Escanear',
      body: 'Aqui empieza el flujo principal. NetSentinel detecta tu red y prepara el analisis sin que tengas que configurar herramientas tecnicas.',
    },
    {
      selector: '[data-tour="scan-network"]',
      route: '/scan',
      title: 'Red detectada',
      body: 'Esta tarjeta muestra la red que NetSentinel encontro automaticamente. Si no es la correcta, puedes cambiarla o escribirla manualmente.',
    },
    {
      selector: '[data-tour="scan-profile"]',
      route: '/scan',
      title: 'Nivel de analisis',
      body: 'Elige que tan profunda sera la revision. Rapido sirve para una primera mirada; Completo tarda mas y revisa con mayor detalle.',
    },
    {
      selector: '[data-tour="scan-start"]',
      route: '/scan',
      title: 'Iniciar analisis',
      body: 'Cuando todo este listo, este boton inicia el analisis. Durante el proceso veras progreso claro y detalles tecnicos solo si quieres abrirlos.',
    },
    {
      selector: '[data-tour="nav-history"]',
      route: '/history',
      title: 'Historial',
      body: 'Aqui quedan guardados los analisis anteriores para volver a revisarlos, comparar cambios y abrir reportes.',
    },
    {
      selector: '[data-tour="nav-dashboard"]',
      route: '/dashboard',
      title: 'Panel',
      body: 'El panel resume lo mas importante: dispositivos encontrados, riesgos, vulnerabilidades y actividad reciente.',
    },
    {
      selector: '[data-tour="nav-agent"]',
      route: '/agent',
      title: 'Asistente IA',
      body: 'El asistente ayuda a explicar resultados y proponer siguientes pasos en lenguaje claro.',
    },
  ];

  constructor(private router: Router) {
    window.addEventListener('resize', () => this.refreshSpotlight());
    document.addEventListener('click', (event) => this.handleTargetClick(event), true);
  }

  currentStep(): TutorialStep | null {
    return this.steps[this.index()] ?? null;
  }

  start(): void {
    this.active.set(true);
    this.index.set(0);
    void this.prepareStep();
  }

  stop(): void {
    this.active.set(false);
    this.spotlight.set(null);
    this.missingTarget.set(false);
  }

  next(): void {
    if (this.index() >= this.steps.length - 1) {
      this.stop();
      return;
    }
    this.index.update((i) => i + 1);
    void this.prepareStep();
  }

  back(): void {
    if (this.index() === 0) return;
    this.index.update((i) => i - 1);
    void this.prepareStep();
  }

  async prepareStep(): Promise<void> {
    const step = this.currentStep();
    if (!step) return;
    this.missingTarget.set(false);
    if (step.route && this.router.url.split('?')[0] !== step.route) {
      await this.router.navigate([step.route]);
    }
    setTimeout(() => this.refreshSpotlight(), 180);
  }

  refreshSpotlight(): void {
    if (!this.active()) return;
    const step = this.currentStep();
    if (!step) return;
    const target = document.querySelector<HTMLElement>(step.selector);
    if (!target) {
      this.spotlight.set(null);
      this.missingTarget.set(true);
      return;
    }
    target.scrollIntoView({ block: 'center', inline: 'center', behavior: 'smooth' });
    setTimeout(() => {
      const rect = target.getBoundingClientRect();
      this.spotlight.set({
        top: Math.max(rect.top - 8, 8),
        left: Math.max(rect.left - 8, 8),
        width: rect.width + 16,
        height: rect.height + 16,
      });
      this.missingTarget.set(false);
    }, 180);
  }

  private handleTargetClick(event: Event): void {
    if (!this.active()) return;
    const step = this.currentStep();
    if (!step) return;
    const target = event.target as HTMLElement | null;
    if (!target?.closest(step.selector)) return;
    setTimeout(() => this.next(), 220);
  }
}
