// Mirrors com.recoversense.dashboard DTOs exactly - no reshaping here.
export interface DashboardSummary {
  revenueAtRisk: number;
  recoveredRevenue: number;
  recoveryRate: number;
  verifiedActions: number;
  policyBlocks: number;
}

export interface StrategyMixEntry {
  strategy: string;
  count: number;
}

export interface RecentCaseSummary {
  recoveryCaseId: number;
  externalPaymentId: string;
  amount: number;
  currency: string;
  failureReason: string | null;
  diagnosisCategory: string | null;
  diagnosisConfidence: number | null;
  strategy: string | null;
  policyResult: string | null;
  executionStatus: string | null;
  verificationStatus: string | null;
  caseStatus: string;
  openedAt: string;
}

export interface DashboardResponse {
  summary: DashboardSummary;
  strategyMix: StrategyMixEntry[];
  recentCases: RecentCaseSummary[];
}

export interface AuditEventSummary {
  eventType: string;
  payload: string | null;
  createdAt: string;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8081';

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`);
  if (!response.ok) {
    throw new Error(`${path} failed with status ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export function fetchDashboard(): Promise<DashboardResponse> {
  return getJson<DashboardResponse>('/api/dashboard/metrics');
}

export function fetchAuditTrail(recoveryCaseId: number): Promise<AuditEventSummary[]> {
  return getJson<AuditEventSummary[]>(`/api/dashboard/cases/${recoveryCaseId}/audit`);
}
