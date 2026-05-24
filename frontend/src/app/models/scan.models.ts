export type ScanStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

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

export interface WebFindingDto {
  tool: string;
  url: string;
  statusCode: number | null;
  description: string;
  severity: string;
}

export interface HostDto {
  id: string;
  ip: string;
  hostname: string | null;
  os: string | null;
  macAddress: string | null;
  vendor: string | null;
  ports: PortDto[];
  webFindings: WebFindingDto[];
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

export interface LocalNetworkInterface {
  name: string;
  ip: string;
  subnet: string;
}

export type ScanProfile = 'RAPIDO' | 'ESTANDAR' | 'COMPLETO';

export interface ScanStatusResponse {
  id: string;
  target: string;
  status: ScanStatus;
  startedAt: string;
  completedAt: string | null;
  errorMessage: string | null;
  riskLevel: string | null;
  riskScore: number | null;
}

export interface DashboardResponse {
  totalScans: number;
  completedScans: number;
  failedScans: number;
  activeScans: number;
  totalHosts: number;
  totalCves: number;
  cvesBySeverity: Record<string, number>;
  averageRiskScore: number;
  recentScans: RecentScanEntry[];
}

export interface RecentScanEntry {
  id: string;
  target: string;
  status: string;
  startedAt: string;
  completedAt: string | null;
  riskLevel: string | null;
  riskScore: number | null;
  hostCount: number;
}

export interface AssetDto {
  ip: string;
  hostname: string | null;
  os: string | null;
  macAddress: string | null;
  vendor: string | null;
  lastScanDate: string | null;
  lastScanId: string | null;
  openPorts: number;
  totalCves: number;
  criticalCves: number;
  highCves: number;
  riskLevel: string;
}

export interface ScanCompareResponse {
  scanAId: string;
  scanBId: string;
  scanATarget: string;
  scanBTarget: string;
  scanADate: string;
  scanBDate: string;
  newFindings: ComparedFinding[];
  resolvedFindings: ComparedFinding[];
  persistentFindings: ComparedFinding[];
  newCount: number;
  resolvedCount: number;
  persistentCount: number;
}

export interface ComparedFinding {
  severity: string;
  title: string;
  host: string;
  port: number;
  service: string;
  relatedCves: string[];
}

export type FindingStatusMap = Record<string, string>;

export interface AgentChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface AgentSseEvent {
  type: 'text' | 'tool_use' | 'tool_result' | 'done' | 'error';
  content?: string;
  name?: string;
  input?: unknown;
  result?: string;
  message?: string;
}

export interface AgentChatRequest {
  apiKey: string;
  messages: AgentChatMessage[];
}
