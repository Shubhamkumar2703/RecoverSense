import { useCallback, useEffect, useState } from 'react';
import './App.css';
import {
  fetchAuditTrail,
  fetchDashboard,
  recoverPayment,
  RecoveryApiError,
  type AuditEventSummary,
  type DashboardResponse,
  type RecentCaseSummary,
  type RecoveryResponse,
} from './api';

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

// Plain-language outcome text. RECOVERED's headline is the only one that
// says "Recovered" - every other outcome describes what actually happened
// instead, so a reader never mistakes "executed" for "verified" or
// "verified" for "recovered".
const OUTCOME_INFO: Record<string, { headline: string; tone: PillTone }> = {
  RECOVERED: { headline: 'Recovered successfully', tone: 'good' },
  BLOCKED: { headline: 'Recovery blocked by policy', tone: 'neutral' },
  EXECUTION_FAILED: { headline: 'Recovery action could not be executed', tone: 'critical' },
  VERIFICATION_FAILED: { headline: 'Action executed, but recovery was not verified', tone: 'warn' },
  EXECUTION_UNAVAILABLE: { headline: 'Recovery provider is currently unavailable', tone: 'warn' },
  VERIFICATION_UNAVAILABLE: { headline: 'Recovery could not be verified right now', tone: 'warn' },
};

function RecoveryResultCard({
  result,
  errorMessage,
  onDismiss,
}: {
  result: RecoveryResponse | null;
  errorMessage: string | null;
  onDismiss: () => void;
}) {
  if (!result && !errorMessage) return null;
  const outcome = result ? OUTCOME_INFO[result.outcome] : null;

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

function DecisionPanel({ recoveryCase }: { recoveryCase: RecentCaseSummary }) {
  const confidence =
    recoveryCase.diagnosisConfidence !== null ? `${(recoveryCase.diagnosisConfidence * 100).toFixed(0)}%` : '—';

  return (
    <div className="decision">
      <div className="eyebrow">Selected case · {recoveryCase.externalPaymentId}</div>
      <h2>{strategyLabel(recoveryCase.strategy)}</h2>
      <p>{recoveryCase.failureReason ?? 'No failure reason recorded.'}</p>
      <div className="decision-row">
        <span>AI diagnosis</span>
        <b>
          {recoveryCase.diagnosisCategory ?? '—'} · {confidence}
        </b>
      </div>
      <div className="decision-row">
        <span>Policy</span>
        <b>{recoveryCase.policyResult ?? '—'}</b>
      </div>
      <div className="decision-row">
        <span>Amount</span>
        <b>{formatCurrency(recoveryCase.amount, recoveryCase.currency)}</b>
      </div>
      <div className="decision-row">
        <span>Verification</span>
        <b>{recoveryCase.verificationStatus ?? '—'}</b>
      </div>
    </div>
  );
}

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
            <div key={index}>
              <span className="time">{formatTime(event.createdAt)}</span> <span className="event">{event.eventType}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default function App() {
  const [dashboard, setDashboard] = useState<DashboardResponse | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectedCaseId, setSelectedCaseId] = useState<number | null>(null);
  const [auditEvents, setAuditEvents] = useState<AuditEventSummary[]>([]);
  const [recoveringPaymentId, setRecoveringPaymentId] = useState<number | null>(null);
  const [recoveryResult, setRecoveryResult] = useState<RecoveryResponse | null>(null);
  const [recoveryErrorText, setRecoveryErrorText] = useState<string | null>(null);

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
  }, [loadDashboard]);

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
      setSelectedCaseId(result.recoveryCaseId);
      loadAuditTrail(result.recoveryCaseId);
    } catch (error) {
      setRecoveryErrorText(recoveryErrorMessage(error));
    } finally {
      setRecoveringPaymentId(null);
    }
  }

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
          <a className="active" href="#">
            Overview
          </a>
          <span className="disabled">At-risk payments</span>
          <span className="disabled">Recovery cases</span>
          <span className="disabled">Audit trail</span>
          <span className="disabled">Metrics</span>
        </div>

        <div className="nav-title">System</div>
        <div className="nav">
          <span className="disabled">Policy rules</span>
          <span className="disabled">Integrations</span>
          <span className="disabled">Settings</span>
        </div>

        <div className="status">
          <span className={`dot ${loadError ? 'err' : 'ok'}`}></span>
          {loadError ? 'Backend unreachable' : 'Connected · real persisted data'}
        </div>
      </aside>

      <main>
        <div className="topbar">
          <h1>Recovery Overview</h1>
          <div className="sub">Live view of revenue at risk and recovery decisions</div>
        </div>

        {loadError && <div className="error-banner">Could not load dashboard data: {loadError}</div>}

        <RecoveryResultCard
          result={recoveryResult}
          errorMessage={recoveryErrorText}
          onDismiss={() => {
            setRecoveryResult(null);
            setRecoveryErrorText(null);
          }}
        />

        {!dashboard ? (
          !loadError && <div className="empty-state">Loading…</div>
        ) : (
          <>
            <section className="metrics">
              <div className="card">
                <div className="metric-label">Revenue at risk</div>
                <div className="metric-value">{formatCurrency(dashboard.summary.revenueAtRisk, 'INR')}</div>
                <div className="metric-foot">{dashboard.recentCases.length} recovery cases</div>
              </div>
              <div className="card">
                <div className="metric-label">Recovered</div>
                <div className="metric-value">{formatCurrency(dashboard.summary.recoveredRevenue, 'INR')}</div>
                <div className="metric-foot positive">{formatPercent(dashboard.summary.recoveryRate)} recovery rate</div>
              </div>
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
                <div className="metric-label">Policy blocks</div>
                <div className="metric-value">{dashboard.summary.policyBlocks}</div>
                <div className="metric-foot">decisions blocked by policy</div>
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

            <section className="card table-card">
              <div className="table-head">
                <div className="card-title">Recent recovery cases</div>
                <div className="muted">Showing {dashboard.recentCases.length}</div>
              </div>
              {dashboard.recentCases.length === 0 ? (
                <div className="empty-state">No recovery cases yet.</div>
              ) : (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>Payment</th>
                        <th>Amount</th>
                        <th>Failure</th>
                        <th>AI recommendation</th>
                        <th>Policy</th>
                        <th>Execution</th>
                        <th>Verification</th>
                        <th>Case</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {dashboard.recentCases.map((row) => (
                        <tr
                          key={row.recoveryCaseId}
                          className={row.recoveryCaseId === selectedCaseId ? 'selected' : ''}
                          onClick={() => setSelectedCaseId(row.recoveryCaseId)}
                        >
                          <td>
                            <b>{row.externalPaymentId}</b>
                          </td>
                          <td>{formatCurrency(row.amount, row.currency)}</td>
                          <td>{row.failureReason ?? '—'}</td>
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
                            <button
                              type="button"
                              className="recover-btn"
                              disabled={recoveringPaymentId !== null}
                              onClick={(event) => {
                                event.stopPropagation();
                                handleRecover(row.paymentId);
                              }}
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

            <section className="bottom">
              {selectedCase ? (
                <DecisionPanel recoveryCase={selectedCase} />
              ) : (
                <div className="decision">
                  <div className="eyebrow">No case selected</div>
                  <p>Select a row from the table above to see its decision detail.</p>
                </div>
              )}
              <AuditPanel recoveryCase={selectedCase} events={auditEvents} />
            </section>
          </>
        )}
      </main>
    </div>
  );
}
