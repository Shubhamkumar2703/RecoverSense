// Mirrors com.recoversense.dashboard DTOs exactly - no reshaping here.
export interface DashboardSummary {
  revenueAtRisk: number;
  recoveredRevenue: number;
  recoveryRate: number;
  verifiedActions: number;
  policyBlocks: number;
  failedPaymentsCount: number;
  recoveredCasesCount: number;
  pendingVerificationCount: number;
  executionIssuesCount: number;
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
  // M1.26: "REAL" (Razorpay-synced) or "DEMO" (DemoDataSeeder) - see
  // DashboardMetricsService.classifyDataSource. Never hide this distinction.
  dataSource: string;
  // M1.27: "CLAUDE" or "SIMULATED" (deterministic), or null if not
  // recorded - see DiagnosisSource.parsePrefix. Never labeled "Claude"
  // unless Claude was actually invoked.
  diagnosisSource: string | null;
  // M1.35: the hosted Payment Link URL, persisted server-side (RecoveryAction.providerUrl)
  // so it survives a page refresh/navigation, not just the one recover()
  // response that created it. Null unless a PAYMENT_LINK action exists.
  providerUrl: string | null;
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

// Mirrors com.recoversense.dashboard.AtRiskPaymentSummary exactly (M1.22).
export interface AtRiskPaymentSummary {
  paymentId: number;
  externalPaymentId: string;
  amount: number;
  currency: string;
  failureReason: string | null;
  failedAt: string;
  dataSource: string;
}

// Mirrors com.recoversense.razorpay.RazorpaySyncResponse exactly (M1.26).
export interface RazorpaySyncResponse {
  available: boolean;
  imported: number;
  skipped: number;
  message: string | null;
}

// Mirrors com.recoversense.policy.PolicyCheckResult exactly - same shape as
// policy.ts's PolicyCheckEntry (structurally compatible, so policy.ts's
// policyCheckLabel/policyCheckState work on these unchanged), kept as its
// own type here rather than importing policy.ts to avoid a circular import
// (policy.ts already imports AuditEventSummary from this file).
export interface BatchPolicyCheck {
  checkName: string;
  passed: boolean;
  reason: string;
}

// Mirrors com.recoversense.batch.BatchItemResult exactly.
export interface BatchItemResult {
  externalPaymentId: string;
  description: string;
  amount: number;
  failureReason: string | null;
  diagnosisCategory: string;
  diagnosisConfidence: number;
  strategy: string;
  policyResult: string;
  policyChecks: BatchPolicyCheck[];
  outcome: string;
  recoveredAmount: number | null;
}

// Mirrors com.recoversense.batch.BatchMetrics exactly. recoveryRate is a
// fraction (0..1), same convention as DashboardSummary.recoveryRate.
export interface BatchMetrics {
  batchSize: number;
  revenueAtRisk: number;
  policyEligible: number;
  policyBlocked: number;
  actionsAttempted: number;
  verifiedRecoveries: number;
  revenueRecovered: number;
  recoveryRate: number;
}

// Mirrors com.recoversense.batch.BatchSafetySummary exactly - every field is
// expected to always be 0 (see BatchEvaluationService's javadoc); shown to
// prove that, not because a nonzero value is anticipated.
export interface BatchSafetySummary {
  unauthorizedActions: number;
  policyViolations: number;
  duplicatePendingActions: number;
  unverifiedRecoveries: number;
}

// Mirrors com.recoversense.batch.BatchEvaluationResponse exactly.
export interface BatchEvaluationResponse {
  datasetLabel: string;
  metrics: BatchMetrics;
  safety: BatchSafetySummary;
  items: BatchItemResult[];
}

// Mirrors com.recoversense.demo.DemoResetResponse exactly. Only ever returned
// by resetDemoPaymentLink() below - the reset endpoint only exists at all
// under the backend's demo profile (see DemoController), so this type has no
// bearing on non-demo deployments.
export interface DemoResetResponse {
  success: boolean;
  paymentId: string;
  status: string;
}

// Mirrors com.recoversense.recovery.RecoveryResponse exactly (M1.20, +providerUrl M1.25).
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
  // Real Razorpay hosted Payment Link URL - only ever present on the
  // recover() response that just executed a fresh PAYMENT_LINK action
  // (RecoveryAction.providerUrl is request-scoped, never persisted), so a
  // later verify() response never carries it.
  providerUrl: string | null;
  // M1.27: "CLAUDE" or "SIMULATED", or null - same rule as RecentCaseSummary.
  diagnosisSource: string | null;
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

export function fetchAtRiskPayments(): Promise<AtRiskPaymentSummary[]> {
  return getJson<AtRiskPaymentSummary[]>('/api/dashboard/payments/at-risk');
}

// Batch Recovery Evaluation - a pure, side-effect-free computation over a
// fixed, non-persisted, clearly-labeled SIMULATED dataset (see
// BatchEvaluationService). Never touches real payments, never calls
// Razorpay - GET, not POST, because it has no effect to trigger.
export function fetchBatchEvaluation(): Promise<BatchEvaluationResponse> {
  return getJson<BatchEvaluationResponse>('/api/batch/evaluate');
}

// M1.26: pulls real Razorpay Test Mode failed payments into RecoverSense's
// own Payment table (read-only against Razorpay, idempotent locally) - the
// frontend never talks to Razorpay directly. 503 with available:false means
// Razorpay isn't configured on the server, not an error to throw - the
// caller shows that message rather than a generic failure.
export async function syncRazorpayPayments(): Promise<RazorpaySyncResponse> {
  const response = await fetch(`${API_BASE_URL}/api/dashboard/payments/sync`, { method: 'POST' });
  return response.json() as Promise<RazorpaySyncResponse>;
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

// M1.25 phase 2: independently re-verifies the action recover() already
// executed for this case (e.g. after a human pays a real Razorpay Payment
// Link) - never re-executes, never creates another action. Same response
// shape/status handling as recoverPayment: 503 for VERIFICATION_UNAVAILABLE
// still carries a full RecoveryResponse, and a genuine guard rejection
// (404/409) carries an ErrorResponse and throws.
export async function verifyRecovery(recoveryCaseId: number): Promise<RecoveryResponse> {
  const response = await fetch(`${API_BASE_URL}/api/recovery/cases/${recoveryCaseId}/verify`, { method: 'POST' });
  if (!response.ok && response.status !== 503) {
    const body = (await response.json().catch(() => null)) as { message?: string } | null;
    throw new RecoveryApiError(response.status, body?.message ?? `verify failed with status ${response.status}`);
  }
  return response.json() as Promise<RecoveryResponse>;
}

// DemoController only exists as a bean under the backend's demo profile
// (@Profile("demo")) - in every other profile this route is unmapped and
// returns 404, which this resolves to false rather than throwing. Used once
// on load to decide whether to show the "Reset Demo" operator control at
// all; never assumes based on hostname.
export async function checkDemoAvailable(): Promise<boolean> {
  try {
    const response = await fetch(`${API_BASE_URL}/api/demo/status`);
    return response.ok;
  } catch {
    return false;
  }
}

// Demo-only operator convenience: resets exactly pay_demo_payment_link back
// to its seeded FAILED state so the hero demo can be repeated. See
// DemoResetService - scoped server-side to that one payment id, never takes
// a paymentId/caseId from the caller.
export async function resetDemoPaymentLink(): Promise<DemoResetResponse> {
  const response = await fetch(`${API_BASE_URL}/api/demo/reset-payment-link`, { method: 'POST' });
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as { message?: string } | null;
    throw new RecoveryApiError(response.status, body?.message ?? `demo reset failed with status ${response.status}`);
  }
  return response.json() as Promise<DemoResetResponse>;
}
