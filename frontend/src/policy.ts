import type { AuditEventSummary } from './api';

// Mirrors com.recoversense.policy.PolicyCheckResult / PolicyDecision exactly
// as PolicyEngine serializes them into the POLICY_EVALUATED audit event's
// payload (see RecoveryPolicyService) - never reshaped, never guessed. This
// is the only place individual check results exist anywhere in the API: no
// RecoveryResponse/RecentCaseSummary field carries them, so the audit trail
// is the sole source for this UI.
export interface PolicyCheckEntry {
  checkName: string;
  passed: boolean;
  reason: string;
}

export interface PolicyEvaluationPayload {
  checks: PolicyCheckEntry[];
  result: string;
  evaluatedAt: string;
}

// The seven checks are a fixed, named spec (CLAUDE.md #9 / POLICY_SPEC.md) -
// this is a label lookup for a known, stable set of checkName strings, the
// same pattern as STRATEGY_LABELS for strategy codes. It never invents a
// check or a result; an unrecognized checkName still renders using its raw
// string.
const POLICY_CHECK_LABELS: Record<string, { code: string; label: string }> = {
  retry_limit_not_exceeded: { code: 'P01', label: 'Retry limit' },
  subscription_state_valid: { code: 'P02', label: 'Subscription state' },
  customer_active: { code: 'P03', label: 'Customer active' },
  no_pending_reacquisition: { code: 'P04', label: 'No pending reacquisition' },
  amount_within_policy: { code: 'P05', label: 'Amount within policy' },
  not_already_settled_elsewhere: { code: 'P06', label: 'Settlement state' },
  webhook_delay_window_respected: { code: 'P07', label: 'Webhook delay window' },
};

export function policyCheckLabel(checkName: string): { code: string; label: string } {
  return POLICY_CHECK_LABELS[checkName] ?? { code: '—', label: checkName };
}

export type PolicyCheckState = 'PASS' | 'FAIL' | 'UNKNOWN';

// PolicyCheckResult only ever carries a boolean passed - there is no
// separate "unknown evidence" flag in the API. When a required check fails
// specifically because evidence was unavailable (never fabricated, always
// fails closed - see PolicyEngine), its own reason text says so ("... is
// unknown"). Deriving UNKNOWN from that existing text (rather than treating
// every failure the same) is reading data already in the response, not
// inventing a new server-side concept.
export function policyCheckState(check: PolicyCheckEntry): PolicyCheckState {
  if (check.passed) return 'PASS';
  return check.reason.toLowerCase().includes('unknown') ? 'UNKNOWN' : 'FAIL';
}

// Defensive: the payload is a raw JSON string stored on the audit event
// (AuditEventSummary.payload) - this UI must degrade gracefully, never
// crash, if it's ever missing, malformed, or from a shape this build
// doesn't recognize.
export function parsePolicyEvaluation(payload: string | null): PolicyEvaluationPayload | null {
  if (!payload) return null;
  try {
    const parsed: unknown = JSON.parse(payload);
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      Array.isArray((parsed as { checks?: unknown }).checks) &&
      typeof (parsed as { result?: unknown }).result === 'string'
    ) {
      return parsed as PolicyEvaluationPayload;
    }
    return null;
  } catch {
    return null;
  }
}

// The most recent POLICY_EVALUATED event for a case - a case can be
// evaluated more than once (M1.26 retry semantics), and only the latest
// evaluation reflects the case's current policyResult.
export function latestPolicyEvaluation(events: AuditEventSummary[]): PolicyEvaluationPayload | null {
  for (let i = events.length - 1; i >= 0; i -= 1) {
    if (events[i].eventType === 'POLICY_EVALUATED') {
      return parsePolicyEvaluation(events[i].payload);
    }
  }
  return null;
}
