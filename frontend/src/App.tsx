import { useCallback, useEffect, useState } from 'react';
import './App.css';
import {
  checkDemoAvailable,
  fetchAtRiskPayments,
  fetchAuditTrail,
  fetchBatchEvaluation,
  fetchDashboard,
  recoverPayment,
  resetDemoPaymentLink,
  syncRazorpayPayments,
  verifyRecovery,
  RecoveryApiError,
  type AtRiskPaymentSummary,
  type AuditEventSummary,
  type BatchEvaluationResponse,
  type BatchItemResult,
  type DashboardResponse,
  type RecentCaseSummary,
  type RecoveryResponse,
} from './api';
import { latestPolicyEvaluation, policyCheckLabel, policyCheckState, type PolicyEvaluationPayload } from './policy';

const STRATEGY_LABELS: Record<string, string> = {
  PAYMENT_LINK: 'Payment link',
  REACQUIRE_MANDATE: 'Re-acquire mandate',
  WAIT_RETRY: 'Wait / retry',
  STOP: 'Stop',
  ESCALATE: 'Escalate',
};

function strategyLabel(strategy: string | null): string {
  if (!strategy) return '—';
  return STRATEGY_LABELS[strategy] ?? strategy;
}

// M1.27: truthful provider label - never says "Claude" unless Claude was
// actually invoked (see DiagnosisSource.parsePrefix on the backend). This
// build runs without CLAUDE_API_KEY, so every diagnosis is currently
// "Deterministic Demo" - the label reflects whatever the backend recorded,
// not a hardcoded assumption.
function diagnosisSourceLabel(diagnosisSource: string | null): string {
  if (diagnosisSource === 'CLAUDE') return 'Claude';
  if (diagnosisSource === 'SIMULATED') return 'Deterministic Demo';
  return '—';
}

// M1.34: the compact table pill already shows REAL/DEMO (DATA_SOURCE_TONES);
// this pairs it with a short, honest qualifier wherever there's room for one,
// so a reader never has to infer what "REAL" vs "DEMO" actually means. Never
// implies DEMO data is real, never implies more than dataSource itself says.
function dataSourceDescriptor(dataSource: string): string {
  if (dataSource === 'REAL') return 'Razorpay Test Mode';
  if (dataSource === 'DEMO') return 'Seeded scenario';
  return '';
}

function DataSourceBadge({ value }: { value: string }) {
  const descriptor = dataSourceDescriptor(value);
  return (
    <span className={`source-badge ${DATA_SOURCE_TONES[value] ?? 'neutral'}`}>
      <b>{value}</b>
      {descriptor && <span> · {descriptor}</span>}
    </span>
  );
}

function formatCurrency(amount: number, currency: string): string {
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency, maximumFractionDigits: 0 }).format(amount);
}

function formatPercent(rate: number): string {
  return `${(rate * 100).toFixed(1)}%`;
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-IN', { hour12: false });
}

type PillTone = 'good' | 'warn' | 'neutral' | 'critical';

function pillFor(value: string | null, tones: Partial<Record<string, PillTone>>): { label: string; tone: PillTone } {
  if (!value) return { label: '—', tone: 'neutral' };
  return { label: value, tone: tones[value] ?? 'neutral' };
}

function Pill({ value, tones }: { value: string | null; tones: Partial<Record<string, PillTone>> }) {
  const { label, tone } = pillFor(value, tones);
  return <span className={`pill ${tone}`}>{label}</span>;
}

const POLICY_TONES: Partial<Record<string, PillTone>> = { ALLOWED: 'good', BLOCKED: 'critical' };
const EXECUTION_TONES: Partial<Record<string, PillTone>> = { EXECUTED: 'good', PENDING: 'warn', FAILED: 'critical' };
const VERIFICATION_TONES: Partial<Record<string, PillTone>> = {
  VERIFIED: 'good',
  UNVERIFIED: 'warn',
  FAILED: 'critical',
};
const CASE_STATUS_TONES: Partial<Record<string, PillTone>> = {
  RECOVERED: 'good',
  OPEN: 'warn',
  CLOSED: 'neutral',
  FAILED: 'critical',
};
// M1.26: REAL (Razorpay-synced) vs DEMO (DemoDataSeeder) - never hidden, see
// DashboardMetricsService.classifyDataSource.
const DATA_SOURCE_TONES: Partial<Record<string, PillTone>> = { REAL: 'good', DEMO: 'neutral' };

// Plain-language outcome text. RECOVERED's headline is the only one that
// says "Recovered" - every other outcome describes what actually happened
// instead, so a reader never mistakes "executed" for "verified" or
// "verified" for "recovered".
const OUTCOME_INFO: Record<string, { headline: string; tone: PillTone }> = {
  RECOVERED: { headline: 'Recovered successfully', tone: 'good' },
  BLOCKED: { headline: 'Recovery blocked by policy', tone: 'neutral' },
  EXECUTION_FAILED: { headline: 'Recovery action could not be executed', tone: 'critical' },
  // Not "recovered" and not "failed" - a real Payment Link was created and
  // is now waiting on a human to pay it; verification is a deliberate next
  // step, never automatic (M1.25).
  EXECUTED_AWAITING_VERIFICATION: { headline: 'Payment link created - awaiting payment', tone: 'warn' },
  // M1.26: not permanently failed - most often means "not paid yet". Verify
  // payment remains available so the operator can check again once paid.
  VERIFICATION_FAILED: { headline: 'Not verified yet - has the payment been completed?', tone: 'warn' },
  EXECUTION_UNAVAILABLE: { headline: 'Recovery provider is currently unavailable', tone: 'warn' },
  VERIFICATION_UNAVAILABLE: { headline: 'Recovery could not be verified right now', tone: 'warn' },
};

// M1.34: policy explainability. The individual P01-P07 checks live only in
// the audit trail's POLICY_EVALUATED payload (see policy.ts) - neither
// RecoveryResponse nor RecentCaseSummary carries them, so this section reads
// from auditEvents rather than the case row. When that payload can't be
// parsed (older event, unexpected shape), this falls back to the plain
// policyResult pill alone rather than showing broken or invented detail.
function PolicyDecisionSection({
  policyResult,
  evaluation,
}: {
  policyResult: string | null;
  evaluation: PolicyEvaluationPayload | null;
}) {
  if (!policyResult) return null;
  const blocked = policyResult === 'BLOCKED';
  const checks = evaluation?.checks ?? [];
  const passedCount = checks.filter((c) => policyCheckState(c) === 'PASS').length;
  const nonPassedCount = checks.length - passedCount;

  return (
    <div className="card policy-decision">
      <div className="card-header">
        <div className="card-title">Policy decision</div>
        <span className={`pill ${blocked ? 'critical' : 'good'}`}>{policyResult}</span>
      </div>
      <p className="policy-decision-summary">
        {blocked
          ? 'RecoverSense did not execute the recommended recovery because required evidence could not be verified.'
          : 'RecoverSense evaluated all required checks and allowed this recovery to proceed.'}
      </p>
      {checks.length > 0 && (
        <>
          <div className="policy-checks">
            {checks.map((check) => {
              const { code, label } = policyCheckLabel(check.checkName);
              const state = policyCheckState(check);
              const stateTone: PillTone = state === 'PASS' ? 'good' : state === 'UNKNOWN' ? 'warn' : 'critical';
              const mark = state === 'PASS' ? '✓' : '!';
              if (state === 'PASS') {
                return (
                  <div className="policy-check" key={check.checkName}>
                    <div className="policy-check-row">
                      <span className={`check-mark ${stateTone}`}>{mark}</span>
                      <span className="check-code">{code}</span>
                      <span className="check-label">{label}</span>
                      <span className={`pill ${stateTone}`}>{state}</span>
                    </div>
                  </div>
                );
              }
              return (
                <details className="policy-check expandable" key={check.checkName}>
                  <summary className="policy-check-row">
                    <span className={`check-mark ${stateTone}`}>{mark}</span>
                    <span className="check-code">{code}</span>
                    <span className="check-label">{label}</span>
                    <span className={`pill ${stateTone}`}>{state}</span>
                    <span className="check-chevron">›</span>
                  </summary>
                  <div className="policy-check-detail">
                    <div>
                      <span>Status</span>
                      <b>{state}</b>
                    </div>
                    <div>
                      <span>Reason</span>
                      <b>{check.reason}</b>
                    </div>
                    <div>
                      <span>Impact</span>
                      <b>Recovery blocked</b>
                    </div>
                  </div>
                </details>
              );
            })}
          </div>
          <div className="policy-checks-foot muted">
            {passedCount} passed{nonPassedCount > 0 ? ` · ${nonPassedCount} needs attention` : ''}
          </div>
        </>
      )}
      {blocked && <div className="no-action-banner">No financial action executed</div>}
    </div>
  );
}

// M1.34: reinforces the fixed pipeline order (CLAUDE.md #9) and specifically
// that EXECUTED never implies RECOVERED - each step's state is derived
// purely from fields already on RecentCaseSummary, never invented, and works
// identically for a BLOCKED case (later steps simply never reached) as for a
// fully RECOVERED one.
type StepState = 'done' | 'blocked' | 'pending';

function lifecycleStep(label: string, state: StepState, detail: string) {
  return { label, state, detail };
}

function LifecycleStepper({ recoveryCase }: { recoveryCase: RecentCaseSummary }) {
  const steps = [
    lifecycleStep('Diagnosis', recoveryCase.diagnosisCategory ? 'done' : 'pending', recoveryCase.diagnosisCategory ?? 'Not yet diagnosed'),
    lifecycleStep('Strategy', recoveryCase.strategy ? 'done' : 'pending', strategyLabel(recoveryCase.strategy)),
    lifecycleStep(
      'Policy',
      recoveryCase.policyResult === 'ALLOWED' ? 'done' : recoveryCase.policyResult === 'BLOCKED' ? 'blocked' : 'pending',
      recoveryCase.policyResult ?? 'Not yet evaluated',
    ),
    lifecycleStep(
      'Execution',
      recoveryCase.executionStatus === 'EXECUTED' ? 'done' : recoveryCase.executionStatus === 'FAILED' ? 'blocked' : 'pending',
      recoveryCase.executionStatus ?? 'Not executed',
    ),
    lifecycleStep(
      'Verification',
      recoveryCase.verificationStatus === 'VERIFIED' ? 'done' : recoveryCase.verificationStatus === 'FAILED' ? 'blocked' : 'pending',
      recoveryCase.verificationStatus ?? 'Not verified',
    ),
    lifecycleStep(
      'Outcome',
      recoveryCase.caseStatus === 'RECOVERED' ? 'done' : recoveryCase.caseStatus === 'FAILED' ? 'blocked' : 'pending',
      recoveryCase.caseStatus,
    ),
  ];

  return (
    <div className="lifecycle">
      {steps.map((step) => (
        <div className={`lifecycle-step ${step.state}`} key={step.label}>
          <span className="lifecycle-mark">{step.state === 'done' ? '✓' : step.state === 'blocked' ? '✕' : '·'}</span>
          <span className="lifecycle-label">{step.label}</span>
          <span className="lifecycle-detail">{step.detail}</span>
        </div>
      ))}
      {recoveryCase.caseStatus === 'RECOVERED' && (
        <div className="recovery-completed-banner">
          <span>Recovery completed</span>
          <b>{formatCurrency(recoveryCase.amount, recoveryCase.currency)} recovered</b>
        </div>
      )}
    </div>
  );
}

function RecoveryResultCard({
  result,
  errorMessage,
  onDismiss,
  onVerify,
  verifying,
}: {
  result: RecoveryResponse | null;
  errorMessage: string | null;
  onDismiss: () => void;
  onVerify: (recoveryCaseId: number) => void;
  verifying: boolean;
}) {
  if (!result && !errorMessage) return null;
  const outcome = result ? OUTCOME_INFO[result.outcome] : null;
  // Only the exact moment recover() just created a fresh Payment Link
  // carries a URL - never reconstructed, never shown once stale.
  const canOpenPaymentLink = result?.providerUrl != null;
  // M1.26: FAILED is not terminal (see RecoveryActionVerificationService) -
  // a real Payment Link that was checked too early can be verified again
  // once actually paid, so Verify stays available after VERIFICATION_FAILED
  // too. Only RECOVERED (already VERIFIED) removes it.
  const canVerify = result?.outcome === 'EXECUTED_AWAITING_VERIFICATION' || result?.outcome === 'VERIFICATION_FAILED';
  const isRetry = result?.outcome === 'VERIFICATION_FAILED';

  return (
    <div className="card recovery-result">
      <div className="card-header">
        <div className="card-title">Recovery result</div>
        <button type="button" className="dismiss-btn" onClick={onDismiss} aria-label="Dismiss">
          ×
        </button>
      </div>
      {errorMessage ? (
        <div className="error-banner">{errorMessage}</div>
      ) : result ? (
        <>
          <div className="outcome-headline">
            <span className={`pill ${outcome?.tone ?? 'neutral'}`}>{result.outcome}</span>
            <span>{outcome?.headline ?? result.outcome}</span>
          </div>
          <div className="decision-row">
            <span>Diagnosis</span>
            <b>{result.diagnosisCategory ?? '—'}</b>
          </div>
          <div className="decision-row">
            <span>Provider</span>
            <b>{diagnosisSourceLabel(result.diagnosisSource)}</b>
          </div>
          <div className="decision-row">
            <span>Strategy</span>
            <b>{strategyLabel(result.strategy)}</b>
          </div>
          <div className="decision-row">
            <span>Policy</span>
            <Pill value={result.policyResult} tones={POLICY_TONES} />
          </div>
          <div className="decision-row">
            <span>Execution</span>
            <Pill value={result.executionStatus} tones={EXECUTION_TONES} />
          </div>
          <div className="decision-row">
            <span>Verification</span>
            <Pill value={result.verificationStatus} tones={VERIFICATION_TONES} />
          </div>
          {(canOpenPaymentLink || canVerify) && (
            <div className="recovery-result-actions">
              {canOpenPaymentLink && (
                <a className="recover-btn" href={result.providerUrl ?? '#'} target="_blank" rel="noreferrer">
                  Open payment link
                </a>
              )}
              {canVerify && (
                <button
                  type="button"
                  className="recover-btn"
                  disabled={verifying}
                  onClick={() => onVerify(result.recoveryCaseId)}
                >
                  {verifying ? 'Verifying…' : isRetry ? 'Verify again' : 'Verify payment'}
                </button>
              )}
            </div>
          )}
          {canVerify && (
            <p className="muted" style={{ marginTop: 10 }}>
              {isRetry
                ? 'Not verified yet - if the payment has since been completed, click Verify again. RecoverSense re-fetches the real Razorpay state independently each time; nothing is assumed from a previous check.'
                : 'Complete the Test Mode payment on the hosted link, then click Verify payment - RecoverSense will independently re-fetch the real Razorpay state before counting this as recovered.'}
            </p>
          )}
          {result.outcome === 'BLOCKED' && (
            <p className="muted" style={{ marginTop: 10 }}>
              Blocked by policy - see Audit Trail for this case to see exactly which of the 7 checks failed and why.
            </p>
          )}
        </>
      ) : null}
    </div>
  );
}

function recoveryErrorMessage(error: unknown): string {
  if (error instanceof RecoveryApiError) {
    if (error.status === 404) return 'Payment not found.';
    if (error.status === 409) return 'This payment cannot be recovered in its current state.';
  }
  return 'Something went wrong while starting recovery. Please try again.';
}

// M1.35: derives entirely from the persisted RecentCaseSummary.providerUrl -
// never from the ephemeral recover()/verify() result, so it survives a page
// refresh or navigating away and back. Never triggers execute()/recover()
// itself: if this is null, the case simply has no Payment Link yet (or
// never will, e.g. it was blocked) - the operator must explicitly click
// Recover for that, same as any other case.
function PaymentLinkCard({ recoveryCase }: { recoveryCase: RecentCaseSummary }) {
  const [copied, setCopied] = useState(false);

  if (!recoveryCase.providerUrl || recoveryCase.executionStatus !== 'EXECUTED') {
    return null;
  }
  const verified = recoveryCase.verificationStatus === 'VERIFIED';

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(recoveryCase.providerUrl!);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard permission can be denied by the browser - the link is
      // still right there to select/copy manually, so this fails silently
      // rather than showing an error banner for a non-critical convenience.
    }
  }

  return (
    <div className={`payment-link-card ${verified ? 'verified' : 'pending'}`}>
      <div className="payment-link-head">
        <span className="payment-link-title">Payment link</span>
        <span className={`pill ${verified ? 'good' : 'warn'}`}>
          {verified ? 'Verified' : 'Awaiting customer payment'}
        </span>
      </div>
      <div className="payment-link-amount">{formatCurrency(recoveryCase.amount, recoveryCase.currency)} recovery payment</div>
      <div className="payment-link-actions">
        <a className="recover-btn" href={recoveryCase.providerUrl} target="_blank" rel="noreferrer">
          Open payment link ↗
        </a>
        <button type="button" className="recover-btn secondary" onClick={handleCopy}>
          {copied ? 'Copied' : 'Copy link'}
        </button>
      </div>
      <p className="payment-link-note">
        {verified
          ? 'This payment link is kept as a record of the completed recovery.'
          : 'This payment link remains available until the recovery is verified.'}
      </p>
    </div>
  );
}

function DecisionPanel({ recoveryCase }: { recoveryCase: RecentCaseSummary }) {
  const confidence =
    recoveryCase.diagnosisConfidence !== null ? `${(recoveryCase.diagnosisConfidence * 100).toFixed(0)}%` : '—';

  return (
    <div className="decision">
      <div className="decision-top">
        <div className="eyebrow">Selected case · {recoveryCase.externalPaymentId}</div>
        <DataSourceBadge value={recoveryCase.dataSource} />
      </div>
      <h2>{strategyLabel(recoveryCase.strategy)}</h2>
      <p>{recoveryCase.failureReason ?? 'No failure reason recorded.'}</p>
      <div className="decision-row">
        <span>Diagnosis</span>
        <b>
          {recoveryCase.diagnosisCategory ?? '—'} · {confidence}
        </b>
      </div>
      <div className="decision-row">
        <span>Source</span>
        <b>{diagnosisSourceLabel(recoveryCase.diagnosisSource)}</b>
      </div>
      <div className="decision-row">
        <span>Amount</span>
        <b>{formatCurrency(recoveryCase.amount, recoveryCase.currency)}</b>
      </div>

      <LifecycleStepper recoveryCase={recoveryCase} />
      <PaymentLinkCard recoveryCase={recoveryCase} />
    </div>
  );
}

// M1.34: human-readable labels for the existing, unchanged audit vocabulary
// (see AuditEventSummary's backend Javadoc) - never fabricates or reorders
// events, purely a display transform over eventType.
const AUDIT_EVENT_LABELS: Record<string, string> = {
  RECOVERY_CASE_OPENED: 'Case opened',
  RECOVERY_DECISION_RECORDED: 'Decision recorded',
  POLICY_EVALUATED: 'Policy evaluated',
  ACTION_CREATED: 'Action created',
  ACTION_NOT_CREATED: 'Action not created',
  ACTION_EXECUTION_ATTEMPTED: 'Execution attempted',
  ACTION_EXECUTION_UNAVAILABLE: 'Execution unavailable',
  ACTION_VERIFICATION_ATTEMPTED: 'Verification attempted',
  ACTION_VERIFICATION_UNAVAILABLE: 'Verification unavailable',
  CASE_STATUS_CHANGED: 'Case status changed',
};

function auditEventLabel(eventType: string): string {
  return AUDIT_EVENT_LABELS[eventType] ?? eventType.replaceAll('_', ' ').toLowerCase();
}

// Terminal events worth visually emphasizing in the timeline - recovery
// reaching its final state, or policy declining to create an action at all.
const AUDIT_EMPHASIS_EVENTS = new Set(['CASE_STATUS_CHANGED', 'ACTION_NOT_CREATED']);

function AuditPanel({ recoveryCase, events }: { recoveryCase: RecentCaseSummary | null; events: AuditEventSummary[] }) {
  return (
    <div className="card">
      <div className="card-header">
        <div className="card-title">Decision audit trail</div>
        <div className="muted">{recoveryCase?.externalPaymentId ?? 'no case selected'}</div>
      </div>
      {events.length === 0 ? (
        <div className="empty-state">No audit events for this case yet.</div>
      ) : (
        <div className="audit">
          {events.map((event, index) => (
            <div className={AUDIT_EMPHASIS_EVENTS.has(event.eventType) ? 'audit-row emphasis' : 'audit-row'} key={index}>
              <span className="time">{formatTime(event.createdAt)}</span>
              <span className="event">{auditEventLabel(event.eventType)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function CaseDetailSection({
  selectedCase,
  auditEvents,
}: {
  selectedCase: RecentCaseSummary | null;
  auditEvents: AuditEventSummary[];
}) {
  const evaluation = latestPolicyEvaluation(auditEvents);

  return (
    <section className="bottom">
      {selectedCase ? (
        <DecisionPanel recoveryCase={selectedCase} />
      ) : (
        <div className="decision">
          <div className="eyebrow">No case selected</div>
          <p>Select a row from the table above to see its decision detail.</p>
        </div>
      )}
      {selectedCase?.policyResult && (
        <PolicyDecisionSection policyResult={selectedCase.policyResult} evaluation={evaluation} />
      )}
      <AuditPanel recoveryCase={selectedCase} events={auditEvents} />
    </section>
  );
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString('en-IN', { hour12: false });
}

function CasesTable({
  cases,
  selectedCaseId,
  onSelectCase,
  recoveringPaymentId,
  onRecover,
  verifyingCaseId,
  onVerify,
}: {
  cases: RecentCaseSummary[];
  selectedCaseId: number | null;
  onSelectCase: (caseId: number) => void;
  recoveringPaymentId: number | null;
  onRecover: (paymentId: number) => void;
  verifyingCaseId: number | null;
  onVerify: (recoveryCaseId: number) => void;
}) {
  return (
    <section className="card table-card">
      <div className="table-head">
        <div className="card-title">Recovery cases</div>
        <div className="muted">Showing {cases.length}</div>
      </div>
      {cases.length === 0 ? (
        <div className="empty-state">No recovery cases yet.</div>
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Payment</th>
                <th>Amount</th>
                <th>Failure</th>
                <th>Source</th>
                <th>AI recommendation</th>
                <th>Policy</th>
                <th>Execution</th>
                <th>Verification</th>
                <th>Case</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {cases.map((row) => (
                <tr
                  key={row.recoveryCaseId}
                  className={row.recoveryCaseId === selectedCaseId ? 'selected' : ''}
                  onClick={() => onSelectCase(row.recoveryCaseId)}
                >
                  <td>
                    <b>{row.externalPaymentId}</b>
                  </td>
                  <td>{formatCurrency(row.amount, row.currency)}</td>
                  <td>{row.failureReason ?? '—'}</td>
                  <td>
                    <Pill value={row.dataSource} tones={DATA_SOURCE_TONES} />
                  </td>
                  <td>{strategyLabel(row.strategy)}</td>
                  <td>
                    <Pill value={row.policyResult} tones={POLICY_TONES} />
                  </td>
                  <td>
                    <Pill value={row.executionStatus} tones={EXECUTION_TONES} />
                  </td>
                  <td>
                    <Pill value={row.verificationStatus} tones={VERIFICATION_TONES} />
                  </td>
                  <td>
                    <Pill value={row.caseStatus} tones={CASE_STATUS_TONES} />
                  </td>
                  <td>
                    {row.verificationStatus === 'VERIFIED' || row.caseStatus === 'RECOVERED' ? null : row.caseStatus ===
                        'OPEN' && row.executionStatus === 'EXECUTED' ? (
                      <button
                        type="button"
                        className="recover-btn"
                        disabled={verifyingCaseId !== null}
                        onClick={(event) => {
                          event.stopPropagation();
                          onVerify(row.recoveryCaseId);
                        }}
                      >
                        {verifyingCaseId === row.recoveryCaseId ? 'Verifying…' : 'Verify'}
                      </button>
                    ) : (
                      <button
                        type="button"
                        className="recover-btn"
                        disabled={recoveringPaymentId !== null}
                        onClick={(event) => {
                          event.stopPropagation();
                          onRecover(row.paymentId);
                        }}
                      >
                        {recoveringPaymentId === row.paymentId ? 'Recovering…' : 'Recover'}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

const BATCH_OUTCOME_INFO: Record<string, { label: string; tone: PillTone }> = {
  BLOCKED: { label: 'Blocked', tone: 'critical' },
  ALLOWED_NO_EXECUTABLE_ACTION: { label: 'Allowed - no executable action', tone: 'neutral' },
  EXECUTED_AWAITING_VERIFICATION: { label: 'Executed - unverified', tone: 'warn' },
  VERIFIED_RECOVERED: { label: 'Verified - recovered', tone: 'good' },
};

// Track 03: measured recovery economics across a batch. Every number here
// comes from BatchEvaluationService running the real, unmodified
// DiagnosisEngine/StrategyRouter/PolicyEngine over a fixed, non-persisted,
// clearly-labeled SIMULATED dataset - never real Razorpay transactions, and
// never the same thing as the real hero Payment Link recovery shown
// elsewhere in this app. revenueRecovered only ever sums items whose
// outcome is VERIFIED_RECOVERED - an EXECUTED_AWAITING_VERIFICATION row is
// shown, with its amount visible, specifically to make "execution is not
// recovery" legible in the table itself, not just asserted in prose.
function BatchEvaluationView({
  evaluation,
  loading,
  errorMessage,
  onRefresh,
}: {
  evaluation: BatchEvaluationResponse | null;
  loading: boolean;
  errorMessage: string | null;
  onRefresh: () => void;
}) {
  return (
    <>
      <section className="card">
        <div className="card-header">
          <div className="card-title">Batch Recovery Evaluation</div>
          <button type="button" className="recover-btn secondary" disabled={loading} onClick={onRefresh}>
            {loading ? 'Evaluating…' : 'Run batch evaluation'}
          </button>
        </div>
        <p className="muted">
          {evaluation?.datasetLabel ?? 'Evaluation dataset · SIMULATED - not real Razorpay transactions'}
        </p>
      </section>

      {errorMessage && <div className="error-banner">Could not load batch evaluation: {errorMessage}</div>}

      {!evaluation ? (
        !loading && !errorMessage && <div className="empty-state">Click "Run batch evaluation" to compute measured results.</div>
      ) : (
        <>
          <section className="metrics hero-metrics">
            <div className="card metric-hero">
              <div className="metric-label">Revenue at risk</div>
              <div className="metric-value">{formatCurrency(evaluation.metrics.revenueAtRisk, 'INR')}</div>
              <div className="metric-foot">{evaluation.metrics.batchSize} payments evaluated</div>
            </div>
            <div className="card metric-hero good">
              <div className="metric-label">Recovered</div>
              <div className="metric-value">{formatCurrency(evaluation.metrics.revenueRecovered, 'INR')}</div>
              <div className="metric-foot positive">verified only - execution alone never counts</div>
            </div>
            <div className="card metric-hero good">
              <div className="metric-label">Recovery rate</div>
              <div className="metric-value">{formatPercent(evaluation.metrics.recoveryRate)}</div>
              <div className="metric-foot">recovered / at risk</div>
            </div>
            <div className="card metric-hero warn">
              <div className="metric-label">Policy blocked</div>
              <div className="metric-value">{evaluation.metrics.policyBlocked}</div>
              <div className="metric-foot">no financial action executed</div>
            </div>
          </section>

          <section className="grid">
            <div className="card">
              <div className="card-header">
                <div className="card-title">Recovery outcomes</div>
              </div>
              <div className="breakdown">
                <div className="break-item">
                  <div className="break-num">{evaluation.metrics.policyEligible}</div>
                  <div className="break-name">Eligible</div>
                </div>
                <div className="break-item">
                  <div className="break-num">{evaluation.metrics.policyBlocked}</div>
                  <div className="break-name">Blocked</div>
                </div>
                <div className="break-item">
                  <div className="break-num">{evaluation.metrics.actionsAttempted}</div>
                  <div className="break-name">Actions</div>
                </div>
                <div className="break-item">
                  <div className="break-num">{evaluation.metrics.verifiedRecoveries}</div>
                  <div className="break-name">Verified</div>
                </div>
              </div>
            </div>

            <div className="card">
              <div className="card-header">
                <div className="card-title">Safety</div>
                <div className="muted">structural invariants - see each row's evidence below</div>
              </div>
              <div className="breakdown">
                <div className="break-item">
                  <div className="break-num">{evaluation.safety.policyViolations}</div>
                  <div className="break-name">Policy violations</div>
                </div>
                <div className="break-item">
                  <div className="break-num">{evaluation.safety.unauthorizedActions}</div>
                  <div className="break-name">Unauthorized actions</div>
                </div>
                <div className="break-item">
                  <div className="break-num">{evaluation.safety.duplicatePendingActions}</div>
                  <div className="break-name">Duplicate pending actions</div>
                </div>
                <div className="break-item">
                  <div className="break-num">{evaluation.safety.unverifiedRecoveries}</div>
                  <div className="break-name">Unverified recoveries</div>
                </div>
              </div>
            </div>
          </section>

          <BatchCaseBreakdownTable items={evaluation.items} />
        </>
      )}
    </>
  );
}

function BatchCaseBreakdownTable({ items }: { items: BatchItemResult[] }) {
  return (
    <section className="card table-card">
      <div className="table-head">
        <div className="card-title">Case breakdown</div>
        <div className="muted">Showing {items.length}</div>
      </div>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Payment</th>
              <th>Amount</th>
              <th>Diagnosis</th>
              <th>Strategy</th>
              <th>Policy</th>
              <th>Outcome</th>
              <th>Recovered</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => {
              const outcomeInfo = BATCH_OUTCOME_INFO[item.outcome] ?? { label: item.outcome, tone: 'neutral' as PillTone };
              const failedChecks = item.policyChecks.filter((check) => !check.passed);
              return (
                <tr key={item.externalPaymentId}>
                  <td>
                    <b>{item.externalPaymentId}</b>
                    <div className="muted">{item.description}</div>
                  </td>
                  <td>{formatCurrency(item.amount, 'INR')}</td>
                  <td>{item.diagnosisCategory}</td>
                  <td>{strategyLabel(item.strategy)}</td>
                  <td>
                    <Pill value={item.policyResult} tones={POLICY_TONES} />
                    {failedChecks.length > 0 && (
                      <div className="muted">{policyCheckLabel(failedChecks[0].checkName).label}</div>
                    )}
                  </td>
                  <td>
                    <span className={`pill ${outcomeInfo.tone}`}>{outcomeInfo.label}</span>
                  </td>
                  <td>{item.recoveredAmount != null ? formatCurrency(item.recoveredAmount, 'INR') : '—'}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

// Static, honest description of what's actually wired up - not a live health
// check (no backend endpoint exists for this, and M1.24 adds none). Every
// status a browser cannot actually verify (Razorpay/Claude credentials are
// server-side only and never exposed to the frontend) uses neutral wording
// rather than a connected/disconnected claim - see README.md #16/#17, which
// this mirrors.
function IntegrationsView() {
  const items: { name: string; status: string; tone: PillTone; detail: string }[] = [
    {
      name: 'Razorpay — Payment Links',
      status: 'Available in backend',
      tone: 'neutral',
      detail:
        'Execution wires a real Razorpay Test Mode Payment Link call when razorpay.key-id/key-secret are configured server-side. Credentials are never exposed to the browser, so this page cannot show a live connected/disconnected status.',
    },
    {
      name: 'Razorpay — Payment Link verification',
      status: 'Available in backend',
      tone: 'neutral',
      detail: 'Verification independently re-fetches the Payment Link state from Razorpay rather than trusting the execution call\'s own success response. A prior unpaid check is not permanent — verifying again after the customer pays is supported.',
    },
    {
      name: 'Razorpay — payment ingestion (sync)',
      status: 'Available in backend',
      tone: 'neutral',
      detail: 'Sync Razorpay Test Mode reads real failed payments from Razorpay (GET /v1/payments) and inserts any not already known locally. Read-only against Razorpay; never creates a recovery case or executes anything on its own.',
    },
    {
      name: 'Settlement verification',
      status: 'Unavailable in production — fails closed',
      tone: 'warn',
      detail:
        'No real settlement source is wired for production traffic. The policy check for "already settled elsewhere" evaluates to unknown, and RecoverSense\'s policy engine blocks recovery rather than assume it is safe. The demo profile additionally simulates a NOT_SETTLED answer for one or more explicitly configured payment ids only — never for arbitrary payments, and never outside the demo profile.',
    },
    {
      name: 'Claude diagnosis',
      status: 'Not exposed to browser',
      tone: 'neutral',
      detail:
        'Enabled server-side only when claude.api-key is configured. A deterministic, clearly-labeled simulated classifier is always available as the fallback diagnosis source, so the pipeline never blocks on Claude being unavailable.',
    },
    {
      name: 'Database',
      status: 'Connected',
      tone: 'good',
      detail: 'PostgreSQL. Every figure on this dashboard — at-risk payments, recovery cases, metrics, audit trail — comes from real persisted RecoverSense data, never synthetic numbers.',
    },
  ];

  return (
    <section className="card table-card">
      <div className="table-head">
        <div className="card-title">Integrations</div>
        <div className="muted">Read-only — reflects documented backend configuration, not a live health check</div>
      </div>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Integration</th>
              <th>Status</th>
              <th>What this means</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.name}>
                <td>
                  <b>{item.name}</b>
                </td>
                <td>
                  <span className={`pill ${item.tone}`}>{item.status}</span>
                </td>
                <td style={{ whiteSpace: 'normal', maxWidth: 480 }}>{item.detail}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function AtRiskTable({
  payments,
  recoveringPaymentId,
  onRecover,
  onSync,
  syncing,
  syncMessage,
  demoAvailable,
  onResetDemo,
  resettingDemo,
  resetMessage,
}: {
  payments: AtRiskPaymentSummary[];
  recoveringPaymentId: number | null;
  onRecover: (paymentId: number) => void;
  onSync: () => void;
  syncing: boolean;
  syncMessage: string | null;
  demoAvailable: boolean;
  onResetDemo: () => void;
  resettingDemo: boolean;
  resetMessage: string | null;
}) {
  return (
    <section className="card table-card">
      <div className="table-head at-risk-head">
        <div>
          <div className="card-title">At-risk payments</div>
          <div className="muted">Failed payments not yet under recovery - synced from Razorpay Test Mode or seeded for demo</div>
        </div>
        <div className="at-risk-controls">
          {resetMessage && <span className="sync-status">{resetMessage}</span>}
          {syncMessage && <span className="sync-status">{syncMessage}</span>}
          {demoAvailable && (
            <button type="button" className="recover-btn secondary" disabled={resettingDemo} onClick={onResetDemo}>
              {resettingDemo ? 'Resetting…' : 'Reset Demo'}
            </button>
          )}
          <button type="button" className="recover-btn" disabled={syncing} onClick={onSync}>
            {syncing ? 'Syncing…' : 'Sync Razorpay Test Mode'}
          </button>
          <span className="muted">Showing {payments.length}</span>
        </div>
      </div>
      {payments.length === 0 ? (
        <div className="empty-state">No failed payments currently require recovery.</div>
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Payment</th>
                <th>Source</th>
                <th>Amount</th>
                <th>Failure</th>
                <th>Failed at</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {payments.map((row) => (
                <tr key={row.paymentId}>
                  <td>
                    <b>{row.externalPaymentId}</b>
                  </td>
                  <td>
                    <DataSourceBadge value={row.dataSource} />
                  </td>
                  <td>{formatCurrency(row.amount, row.currency)}</td>
                  <td>{row.failureReason ?? '—'}</td>
                  <td>{formatDate(row.failedAt)}</td>
                  <td>
                    <button
                      type="button"
                      className="recover-btn"
                      disabled={recoveringPaymentId !== null}
                      onClick={() => onRecover(row.paymentId)}
                    >
                      {recoveringPaymentId === row.paymentId ? 'Recovering…' : 'Recover'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

type WorkspaceView = 'overview' | 'at-risk' | 'cases' | 'audit' | 'batch' | 'integrations';

function NavLink({
  view,
  label,
  activeView,
  onSelect,
}: {
  view: WorkspaceView;
  label: string;
  activeView: WorkspaceView;
  onSelect: (view: WorkspaceView) => void;
}) {
  return (
    <a
      className={activeView === view ? 'active' : ''}
      href="#"
      onClick={(event) => {
        event.preventDefault();
        onSelect(view);
      }}
    >
      {label}
    </a>
  );
}

const VIEW_COPY: Record<WorkspaceView, { title: string; subtitle: string }> = {
  overview: { title: 'Recovery Overview', subtitle: 'Live view of revenue at risk and recovery decisions' },
  'at-risk': {
    title: 'At-Risk Payments',
    subtitle: 'Failed payments RecoverSense has not yet recovered - start recovery from here',
  },
  cases: { title: 'Recovery Cases', subtitle: 'Every recovery case RecoverSense has opened, with its decision detail' },
  audit: { title: 'Audit Trail', subtitle: 'The recorded decision trail for a selected recovery case' },
  batch: {
    title: 'Batch Recovery Evaluation',
    subtitle: 'Measured recovery economics across a fixed evaluation dataset - simulated, never real Razorpay transactions',
  },
  integrations: { title: 'Integrations', subtitle: 'What is actually connected, real, or simulated in this build' },
};

export default function App() {
  const [dashboard, setDashboard] = useState<DashboardResponse | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectedCaseId, setSelectedCaseId] = useState<number | null>(null);
  const [auditEvents, setAuditEvents] = useState<AuditEventSummary[]>([]);
  const [recoveringPaymentId, setRecoveringPaymentId] = useState<number | null>(null);
  const [verifyingCaseId, setVerifyingCaseId] = useState<number | null>(null);
  const [recoveryResult, setRecoveryResult] = useState<RecoveryResponse | null>(null);
  const [recoveryErrorText, setRecoveryErrorText] = useState<string | null>(null);
  const [activeView, setActiveView] = useState<WorkspaceView>('overview');
  const [atRiskPayments, setAtRiskPayments] = useState<AtRiskPaymentSummary[]>([]);
  const [syncing, setSyncing] = useState(false);
  const [syncMessage, setSyncMessage] = useState<string | null>(null);
  const [demoAvailable, setDemoAvailable] = useState(false);
  const [resettingDemo, setResettingDemo] = useState(false);
  const [resetMessage, setResetMessage] = useState<string | null>(null);
  const [batchEvaluation, setBatchEvaluation] = useState<BatchEvaluationResponse | null>(null);
  const [batchLoading, setBatchLoading] = useState(false);
  const [batchError, setBatchError] = useState<string | null>(null);

  const loadAtRiskPayments = useCallback(() => {
    return fetchAtRiskPayments()
      .then(setAtRiskPayments)
      .catch(() => setAtRiskPayments([]));
  }, []);

  const loadDashboard = useCallback(() => {
    return fetchDashboard()
      .then((data) => {
        setDashboard(data);
        setLoadError(null);
        return data;
      })
      .catch((error: Error) => {
        setLoadError(error.message);
        return null;
      });
  }, []);

  const loadAuditTrail = useCallback((caseId: number) => {
    fetchAuditTrail(caseId)
      .then(setAuditEvents)
      .catch(() => setAuditEvents([]));
  }, []);

  useEffect(() => {
    loadDashboard().then((data) => {
      if (data && data.recentCases.length > 0) {
        setSelectedCaseId(data.recentCases[0].recoveryCaseId);
      }
    });
    loadAtRiskPayments();
    checkDemoAvailable().then(setDemoAvailable);
  }, [loadDashboard, loadAtRiskPayments]);

  useEffect(() => {
    if (selectedCaseId === null) {
      setAuditEvents([]);
      return;
    }
    loadAuditTrail(selectedCaseId);
  }, [selectedCaseId, loadAuditTrail]);

  async function handleRecover(paymentId: number) {
    setRecoveringPaymentId(paymentId);
    setRecoveryErrorText(null);
    setRecoveryResult(null);
    try {
      const result = await recoverPayment(paymentId);
      setRecoveryResult(result);
      await loadDashboard();
      await loadAtRiskPayments();
      // recoverPayment always creates a brand-new case, so this always
      // differs from whatever was selected before - the selectedCaseId
      // effect below picks it up and loads its audit trail; no need to
      // fetch it again here too (M1.27: this was a redundant duplicate GET).
      setSelectedCaseId(result.recoveryCaseId);
    } catch (error) {
      setRecoveryErrorText(recoveryErrorMessage(error));
    } finally {
      setRecoveringPaymentId(null);
    }
  }

  // M1.25 phase 2: independently re-verifies the action recover() already
  // executed (e.g. after the operator paid the real Razorpay Payment Link
  // shown by handleRecover above). Never re-executes - reuses the exact same
  // result card / refresh sequence as handleRecover so the two phases read
  // as one continuous flow.
  async function handleVerify(recoveryCaseId: number) {
    setVerifyingCaseId(recoveryCaseId);
    setRecoveryErrorText(null);
    try {
      const result = await verifyRecovery(recoveryCaseId);
      setRecoveryResult(result);
      await loadDashboard();
      await loadAtRiskPayments();
      loadAuditTrail(recoveryCaseId);
    } catch (error) {
      setRecoveryErrorText(recoveryErrorMessage(error));
    } finally {
      setVerifyingCaseId(null);
    }
  }

  // M1.26 Phase 1: pulls real Razorpay Test Mode failed payments into
  // RecoverSense - read-only against Razorpay, never calls it directly from
  // here (the backend owns the credentials). Refreshes at-risk/dashboard
  // afterward so newly-imported payments show up immediately.
  async function handleSync() {
    setSyncing(true);
    setSyncMessage(null);
    try {
      const result = await syncRazorpayPayments();
      setSyncMessage(
        result.available
          ? `Imported ${result.imported}, skipped ${result.skipped} already-known payment(s).`
          : (result.message ?? 'Razorpay is not configured on this server.'),
      );
      if (result.available) {
        await loadAtRiskPayments();
        await loadDashboard();
      }
    } catch {
      setSyncMessage('Sync failed. Check the backend logs.');
    } finally {
      setSyncing(false);
    }
  }

  // Demo-only operator convenience (DemoController is only wired under the
  // backend's demo profile - see checkDemoAvailable). Resets exactly the
  // seeded hero payment, never anything the operator picks, so a repeat demo
  // run doesn't require shell/database access.
  async function handleResetDemo() {
    const confirmed = window.confirm(
      'Reset the hero demo payment?\n\nThis will clear the previous recovery attempt for pay_demo_payment_link and return it to FAILED.',
    );
    if (!confirmed) return;

    setResettingDemo(true);
    setResetMessage(null);
    try {
      await resetDemoPaymentLink();
      setResetMessage('Demo payment reset. Ready to recover again.');
      setRecoveryResult(null);
      setRecoveryErrorText(null);
      await loadDashboard();
      await loadAtRiskPayments();
    } catch {
      setResetMessage('Demo reset failed. Please try again.');
    } finally {
      setResettingDemo(false);
    }
  }

  // Pure, side-effect-free, deterministic given the fixed backend dataset -
  // safe to (re-)run on demand, and lazily on first visiting the view rather
  // than on every app load, since nothing else on the dashboard depends on it.
  async function handleRunBatchEvaluation() {
    setBatchLoading(true);
    setBatchError(null);
    try {
      const result = await fetchBatchEvaluation();
      setBatchEvaluation(result);
    } catch (error) {
      setBatchError(error instanceof Error ? error.message : 'Something went wrong while evaluating the batch.');
    } finally {
      setBatchLoading(false);
    }
  }

  useEffect(() => {
    if (activeView === 'batch' && !batchEvaluation && !batchLoading) {
      handleRunBatchEvaluation();
    }
  }, [activeView, batchEvaluation, batchLoading]);

  const selectedCase = dashboard?.recentCases.find((c) => c.recoveryCaseId === selectedCaseId) ?? null;

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          Recover<span>Sense</span>
        </div>
        <div className="tagline">A sixth sense for revenue recovery</div>

        <div className="nav-title">Workspace</div>
        <div className="nav">
          <NavLink view="overview" label="Overview" activeView={activeView} onSelect={setActiveView} />
          <NavLink view="at-risk" label="At-risk payments" activeView={activeView} onSelect={setActiveView} />
          <NavLink view="cases" label="Recovery cases" activeView={activeView} onSelect={setActiveView} />
          <NavLink view="audit" label="Audit trail" activeView={activeView} onSelect={setActiveView} />
          <NavLink view="batch" label="Batch evaluation" activeView={activeView} onSelect={setActiveView} />
        </div>

        <div className="nav-title">System</div>
        <div className="nav">
          <NavLink view="integrations" label="Integrations" activeView={activeView} onSelect={setActiveView} />
        </div>

        <div className="status">
          <span className={`dot ${loadError ? 'err' : 'ok'}`}></span>
          {loadError ? 'Backend unreachable' : 'Connected · real persisted data'}
        </div>
      </aside>

      <main>
        <div className="topbar">
          <h1>{VIEW_COPY[activeView].title}</h1>
          <div className="sub">{VIEW_COPY[activeView].subtitle}</div>
        </div>

        {loadError && <div className="error-banner">Could not load dashboard data: {loadError}</div>}

        <RecoveryResultCard
          result={recoveryResult}
          errorMessage={recoveryErrorText}
          onDismiss={() => {
            setRecoveryResult(null);
            setRecoveryErrorText(null);
          }}
          onVerify={handleVerify}
          verifying={verifyingCaseId !== null}
        />

        {activeView === 'at-risk' ? (
          <AtRiskTable
            payments={atRiskPayments}
            recoveringPaymentId={recoveringPaymentId}
            onRecover={handleRecover}
            onSync={handleSync}
            syncing={syncing}
            syncMessage={syncMessage}
            demoAvailable={demoAvailable}
            onResetDemo={handleResetDemo}
            resettingDemo={resettingDemo}
            resetMessage={resetMessage}
          />
        ) : activeView === 'integrations' ? (
          <IntegrationsView />
        ) : activeView === 'batch' ? (
          <BatchEvaluationView
            evaluation={batchEvaluation}
            loading={batchLoading}
            errorMessage={batchError}
            onRefresh={handleRunBatchEvaluation}
          />
        ) : activeView === 'audit' ? (
          selectedCase ? (
            <AuditPanel recoveryCase={selectedCase} events={auditEvents} />
          ) : (
            <div className="card">
              <div className="empty-state">Select a recovery case to view its decision trail.</div>
            </div>
          )
        ) : activeView === 'cases' ? (
          !dashboard ? (
            !loadError && <div className="empty-state">Loading…</div>
          ) : (
            <>
              <CasesTable
                cases={dashboard.recentCases}
                selectedCaseId={selectedCaseId}
                onSelectCase={setSelectedCaseId}
                recoveringPaymentId={recoveringPaymentId}
                onRecover={handleRecover}
                verifyingCaseId={verifyingCaseId}
                onVerify={handleVerify}
              />
              <CaseDetailSection selectedCase={selectedCase} auditEvents={auditEvents} />
            </>
          )
        ) : !dashboard ? (
          !loadError && <div className="empty-state">Loading…</div>
        ) : (
          <>
            {/* M1.34: business outcomes first (recovered revenue, successful
                recoveries, policy blocks), operational detail after - same
                8 DashboardSummary fields as before, reordered/re-emphasized
                for demo readability, nothing invented. */}
            <section className="metrics hero-metrics">
              <div className="card metric-hero good">
                <div className="metric-label">Recovered</div>
                <div className="metric-value">{formatCurrency(dashboard.summary.recoveredRevenue, 'INR')}</div>
                <div className="metric-foot positive">{formatPercent(dashboard.summary.recoveryRate)} recovery rate</div>
              </div>
              <div className="card metric-hero good">
                <div className="metric-label">Successful recoveries</div>
                <div className="metric-value">{dashboard.summary.recoveredCasesCount}</div>
                <div className="metric-foot">independently verified before counting</div>
              </div>
              <div className="card metric-hero warn">
                <div className="metric-label">Policy blocked</div>
                <div className="metric-value">{dashboard.summary.policyBlocks}</div>
                <div className="metric-foot">no financial action executed</div>
              </div>
              <div className="card metric-hero">
                <div className="metric-label">Revenue at risk</div>
                <div className="metric-value">{formatCurrency(dashboard.summary.revenueAtRisk, 'INR')}</div>
                <div className="metric-foot">{dashboard.recentCases.length} recovery cases</div>
              </div>
            </section>

            <section className="metrics">
              <div className="card">
                <div className="metric-label">Recovery rate</div>
                <div className="metric-value">{formatPercent(dashboard.summary.recoveryRate)}</div>
                <div className="metric-foot">recovered / at risk</div>
              </div>
              <div className="card">
                <div className="metric-label">Verified actions</div>
                <div className="metric-value">{dashboard.summary.verifiedActions}</div>
                <div className="metric-foot">independently verified</div>
              </div>
              <div className="card">
                <div className="metric-label">Failed payments</div>
                <div className="metric-value">{dashboard.summary.failedPaymentsCount}</div>
                <div className="metric-foot">{dashboard.summary.recoveredCasesCount} recovered</div>
              </div>
              <div className="card">
                <div className="metric-label">Awaiting verification</div>
                <div className="metric-value">{dashboard.summary.pendingVerificationCount}</div>
                <div className="metric-foot">executed, not yet verified</div>
              </div>
              <div className="card">
                <div className="metric-label">Execution issues</div>
                <div className="metric-value">{dashboard.summary.executionIssuesCount}</div>
                <div className="metric-foot">failed or unavailable executions</div>
              </div>
            </section>

            <section className="grid">
              <div className="card">
                <div className="card-header">
                  <div className="card-title">Recovery strategy mix</div>
                  <div className="muted">{dashboard.strategyMix.reduce((sum, s) => sum + s.count, 0)} decisions</div>
                </div>
                {dashboard.strategyMix.length === 0 ? (
                  <div className="empty-state">No decisions recorded yet.</div>
                ) : (
                  <div className="breakdown">
                    {dashboard.strategyMix.map((entry) => (
                      <div className="break-item" key={entry.strategy}>
                        <div className="break-num">{entry.count}</div>
                        <div className="break-name">{strategyLabel(entry.strategy)}</div>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              <div className="card">
                <div className="card-header">
                  <div className="card-title">Data source</div>
                </div>
                <p className="muted">
                  All figures on this page come from real persisted RecoverSense recovery data (RecoveryCase,
                  RecoveryDecision, RecoveryAction, AuditEvent) - never synthetic or hardcoded numbers.
                </p>
              </div>
            </section>

            <CasesTable
              cases={dashboard.recentCases}
              selectedCaseId={selectedCaseId}
              onSelectCase={setSelectedCaseId}
              recoveringPaymentId={recoveringPaymentId}
              onRecover={handleRecover}
              verifyingCaseId={verifyingCaseId}
              onVerify={handleVerify}
            />

            <CaseDetailSection selectedCase={selectedCase} auditEvents={auditEvents} />
          </>
        )}
      </main>
    </div>
  );
}
