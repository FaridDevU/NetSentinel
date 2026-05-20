import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, from } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
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
  private readonly base = 'http://127.0.0.1:8080/api';

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
}
