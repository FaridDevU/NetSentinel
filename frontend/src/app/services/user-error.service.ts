import { HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { LangService } from './lang.service';

export type ErrorContext =
  | 'history'
  | 'dashboard'
  | 'assets'
  | 'compare'
  | 'results'
  | 'scan-start'
  | 'scan-poll';

@Injectable({ providedIn: 'root' })
export class UserErrorService {
  constructor(private lang: LangService) {}

  message(err: unknown, context: ErrorContext): string {
    if (err instanceof HttpErrorResponse) {
      if (err.status === 0) {
        return this.lang.t('error.localService.repairing');
      }
      if (err.status === 404) {
        return this.lang.t(this.notFoundKey(context));
      }
      if (err.status >= 500) {
        return this.lang.t('error.localService.internal');
      }
      const apiMessage = this.apiMessage(err);
      if (apiMessage) {
        return this.humanizeApiMessage(apiMessage);
      }
    }

    if (typeof err === 'string' && err.trim()) {
      return this.humanizeApiMessage(err);
    }

    return this.lang.t(this.fallbackKey(context));
  }

  scanFailure(message: string | null | undefined): string {
    if (!message) return this.lang.t('scan.error.scanFailed');
    return this.humanizeApiMessage(message);
  }

  private apiMessage(err: HttpErrorResponse): string | null {
    const body = err.error;
    if (typeof body === 'string') return body;
    if (body && typeof body === 'object' && 'error' in body) {
      const value = (body as { error?: unknown }).error;
      return typeof value === 'string' ? value : null;
    }
    return null;
  }

  private humanizeApiMessage(message: string): string {
    const m = message.toLowerCase();
    if (m.includes('target is required')) {
      return this.lang.t('error.target.required');
    }
    if (m.includes('invalid target')) {
      return this.lang.t('error.target.invalid');
    }
    if (m.includes('scan not found') || m.includes('scan no encontrado')) {
      return this.lang.t('error.scan.notFound');
    }
    if (m.includes('sandbox') || m.includes('connection refused') || m.includes('failed to start')) {
      return this.lang.t('error.scan.engine');
    }
    if (m.includes('timed out') || m.includes('timeout')) {
      return this.lang.t('error.scan.timeout');
    }
    return this.lang.t('error.generic.withRetry');
  }

  private notFoundKey(context: ErrorContext): string {
    if (context === 'results' || context === 'scan-poll') return 'error.scan.notFound';
    return 'error.data.notFound';
  }

  private fallbackKey(context: ErrorContext): string {
    switch (context) {
      case 'history': return 'history.error';
      case 'dashboard': return 'dashboard.error';
      case 'assets': return 'assets.error';
      case 'compare': return 'compare.error';
      case 'results': return 'results.error.loadFailed';
      case 'scan-start': return 'scan.error.startFailed';
      case 'scan-poll': return 'scan.error.lostConnection';
    }
  }
}
