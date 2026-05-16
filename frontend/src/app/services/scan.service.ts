import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  PagedResponse,
  ScanResultsResponse,
  ScanStatusResponse,
  StartScanResponse,
} from '../models/scan.models';

@Injectable({ providedIn: 'root' })
export class ScanService {
  private readonly base = 'http://127.0.0.1:8080/api';

  constructor(private http: HttpClient) {}

  startScan(target: string, parameters: string[]): Observable<StartScanResponse> {
    return this.http.post<StartScanResponse>(`${this.base}/scan/start`, {
      target,
      parameters,
    });
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

  getHistory(
    page: number = 0,
    size: number = 20
  ): Observable<PagedResponse<ScanStatusResponse>> {
    return this.http.get<PagedResponse<ScanStatusResponse>>(
      `${this.base}/history?page=${page}&size=${size}`
    );
  }
}
