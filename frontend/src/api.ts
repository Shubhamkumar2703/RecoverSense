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
  paymentId: number;
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

// Carries the HTTP status alongside the message so callers can distinguish
// "payment not found" (404) from "not in a recoverable state" (409) from an
// unexpected failure, without re-parsing the response body themselves.
export class RecoveryApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'RecoveryApiError';
    this.status = status;
  }
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

// RecoveryController returns 200 for every completed pipeline outcome
// (RECOVERED/BLOCKED/EXECUTION_FAILED/VERIFICATION_FAILED) and 503 for
// EXECUTION_UNAVAILABLE/VERIFICATION_UNAVAILABLE - both still carry a full
// RecoveryResponse body (never an ErrorResponse), so 503 resolves normally
// here too: the caller renders it through the same outcome mapping as any
// other outcome instead of losing that information to a generic error.
// Only a genuine guard rejection (404/409) or a truly unexpected failure
// throws - those responses carry an ErrorResponse{message}, not a
// RecoveryResponse.
export async function recoverPayment(paymentId: number): Promise<RecoveryResponse> {
  const response = await fetch(`${API_BASE_URL}/api/recovery/payments/${paymentId}/recover`, { method: 'POST' });
  if (!response.ok && response.status !== 503) {
    const body = (await response.json().catch(() => null)) as { message?: string } | null;
    throw new RecoveryApiError(response.status, body?.message ?? `recover failed with status ${response.status}`);
  }
  return response.json() as Promise<RecoveryResponse>;
}
