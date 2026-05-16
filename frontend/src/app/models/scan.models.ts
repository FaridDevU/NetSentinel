export type ScanStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface ScanStatusResponse {
  id: string;
  target: string;
  status: ScanStatus;
  startedAt: string;
  completedAt: string | null;
  errorMessage: string | null;
}

export interface CveDto {
  cveId: string;
  description: string;
  cvssScore: number | null;
  cvssVector: string | null;
  nvdUrl: string;
}

export interface PortDto {
  id: string;
  portNumber: number;
  protocol: string;
  state: string;
  service: string | null;
  version: string | null;
  cves: CveDto[];
}

export interface HostDto {
  id: string;
  ip: string;
  hostname: string | null;
  os: string | null;
  macAddress: string | null;
  vendor: string | null;
  ports: PortDto[];
}

export interface AnalysisFinding {
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';
  title: string;
  detail: string;
  host: string;
  port: number;
  service: string;
  relatedCves: string[];
}

export interface AnalysisHostSummary {
  ip: string;
  riskLevel: string;
  openPorts: number;
  totalCves: number;
  summary: string;
}

export interface AnalysisReport {
  riskLevel: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';
  riskScore: number;
  summary: string;
  findings: AnalysisFinding[];
  hostAnalysis: AnalysisHostSummary[];
  recommendations: string[];
}

export interface ScanResultsResponse {
  id: string;
  target: string;
  status: ScanStatus;
  startedAt: string;
  completedAt: string | null;
  hosts: HostDto[];
  analysis: AnalysisReport | null;
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
}

export interface StartScanResponse {
  id: string;
  target: string;
  status: ScanStatus;
}
