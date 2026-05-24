import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, from } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  AgentChatRequest,
  AgentSseEvent,
  AssetDto,
  DashboardResponse,
  FindingStatusMap,
  LocalNetworkInterface,
  PagedResponse,
  ScanCompareResponse,
  ScanResultsResponse,
  ScanStatusResponse,
  StartScanResponse,
} from '../models/scan.models';

@Injectable({ providedIn: 'root' })
export class ScanService {
  private readonly base = 'http://localhost:8080/api';

  constructor(private http: HttpClient) {}

  startScan(target: string, parameters: string[]): Observable<StartScanResponse> {
    return this.http.post<StartScanResponse>(`${this.base}/scan/start`, { target, parameters });
  }

  getStatus(id: string): Observable<ScanStatusResponse> {
    return this.http.get<ScanStatusResponse>(`${this.base}/scan/${id}/status`);
  }

  getResults(id: string): Observable<ScanResultsResponse> {
    return this.http.get<ScanResultsResponse>(`${this.base}/scan/${id}/results`);
  }

  cancelScan(id: string): Observable<unknown> {
    return this.http.post(`${this.base}/scan/${id}/cancel`, {});
  }

  deleteScan(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/scan/${id}`);
  }

  getHistory(page: number = 0, size: number = 20): Observable<PagedResponse<ScanStatusResponse>> {
    return this.http.get<PagedResponse<ScanStatusResponse>>(
      `${this.base}/history?page=${page}&size=${size}`
    );
  }

  getLocalNetworks(): Observable<LocalNetworkInterface[]> {
    const e = (window as any).electron;
    if (e?.getLocalNetworks) {
      return from(e.getLocalNetworks() as Promise<LocalNetworkInterface[]>).pipe(
        catchError(() => this.http.get<LocalNetworkInterface[]>(`${this.base}/network/local`))
      );
    }
    return this.http.get<LocalNetworkInterface[]>(`${this.base}/network/local`);
  }

  getScanLogs(id: string): Observable<{ lines: string[] }> {
    return this.http.get<{ lines: string[] }>(`${this.base}/scan/${id}/logs`);
  }

  getDashboard(): Observable<DashboardResponse> {
    return this.http.get<DashboardResponse>(`${this.base}/dashboard`);
  }

  getAssets(): Observable<AssetDto[]> {
    return this.http.get<AssetDto[]>(`${this.base}/assets`);
  }

  compareScans(a: string, b: string): Observable<ScanCompareResponse> {
    return this.http.get<ScanCompareResponse>(`${this.base}/scan/compare?a=${a}&b=${b}`);
  }

  getFindingStatuses(scanId: string): Observable<FindingStatusMap> {
    return this.http.get<FindingStatusMap>(`${this.base}/scan/${scanId}/findings/statuses`);
  }

  updateFindingStatus(scanId: string, findingKey: string, status: string): Observable<unknown> {
    return this.http.put(`${this.base}/scan/${scanId}/findings/status`, { findingKey, status });
  }

  exportUrl(scanId: string, format: 'pdf' | 'json' | 'csv'): string {
    return `${this.base}/scan/${scanId}/export/${format}`;
  }

  streamAgentChat(request: AgentChatRequest): Observable<AgentSseEvent> {
    const base = this.base;
    return new Observable(observer => {
      const controller = new AbortController();
      fetch(`${base}/agent/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
        signal: controller.signal,
      }).then(response => {
        if (!response.ok || !response.body) {
          observer.error(new Error(`HTTP ${response.status}`));
          return;
        }
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        const pump = (): void => {
          reader.read().then(({ done, value }) => {
            if (done) { observer.complete(); return; }
            buffer += decoder.decode(value, { stream: true });
            const chunks = buffer.split('\n\n');
            buffer = chunks.pop()!;
            for (const chunk of chunks) {
              if (!chunk.trim()) continue;
              let eventType = '';
              let data = '';
              for (const line of chunk.split('\n')) {
                if (line.startsWith('event:')) eventType = line.slice(6).trim();
                else if (line.startsWith('data:')) data = line.slice(5).trim();
              }
              if (eventType && data) {
                try {
                  const parsed = JSON.parse(data);
                  observer.next({ type: eventType as AgentSseEvent['type'], ...parsed });
                } catch { }
              }
            }
            pump();
          }).catch(err => observer.error(err));
        };
        pump();
      }).catch(err => observer.error(err));
      return () => controller.abort();
    });
  }
}
