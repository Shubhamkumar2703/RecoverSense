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

// Mirrors com.recoversense.recovery.RecoveryResponse exactly (M1.20).
export interface RecoveryResponse {
  paymentId: number;
  recoveryCaseId: number;
  caseStatus: string;
  diagnosisCategory: string | null;
  strategy: string | null;
  policyResult: string;
  executionStatus: string | null;
  verificationStatus: string | null;
  externalReference: string | null;
  outcome: string;
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

// Not yet wired into the UI (M1.20 is API-only, no UI changes) - available
// for the next UI milestone to call. RecoveryController returns 200 for
// every completed pipeline outcome (RECOVERED/BLOCKED/EXECUTION_FAILED/
// VERIFICATION_FAILED - see its `outcome` field), so only a genuine guard
// rejection (404/409) or an unavailable provider (503) throws here - never
// collapse a completed-but-unfavorable outcome into an error.
export async function recoverPayment(paymentId: number): Promise<RecoveryResponse> {
  const response = await fetch(`${API_BASE_URL}/api/recovery/payments/${paymentId}/recover`, { method: 'POST' });
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as { message?: string } | null;
    throw new Error(body?.message ?? `recover failed with status ${response.status}`);
  }
  return response.json() as Promise<RecoveryResponse>;
}
