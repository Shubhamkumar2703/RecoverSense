-- RecoverSense core recovery domain schema (M1.1)
-- Lifecycle: Failure -> Diagnosis -> Strategy -> Policy -> Execution -> Verification -> Audit

CREATE TABLE customers (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_customer_id TEXT NOT NULL,
    email               TEXT,
    status              TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_customers_external_customer_id UNIQUE (external_customer_id)
);

CREATE TABLE payments (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_payment_id  TEXT NOT NULL,
    customer_id          BIGINT NOT NULL REFERENCES customers (id),
    subscription_id      TEXT,
    subscription_status  TEXT,
    amount               NUMERIC(12, 2) NOT NULL,
    currency             TEXT NOT NULL DEFAULT 'INR',
    status               TEXT NOT NULL,
    failure_reason       TEXT,
    failed_at            TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_external_payment_id UNIQUE (external_payment_id)
);

CREATE INDEX idx_payments_customer_id ON payments (customer_id);
CREATE INDEX idx_payments_status ON payments (status);

CREATE TABLE recovery_cases (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id  BIGINT NOT NULL REFERENCES payments (id),
    status      TEXT NOT NULL DEFAULT 'OPEN',
    opened_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_recovery_cases_payment_id ON recovery_cases (payment_id);
CREATE INDEX idx_recovery_cases_status ON recovery_cases (status);

-- Duplicate-recovery protection: at most one OPEN case per payment.
CREATE UNIQUE INDEX uq_recovery_cases_open_payment
    ON recovery_cases (payment_id)
    WHERE status = 'OPEN';

CREATE TABLE recovery_decisions (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recovery_case_id    BIGINT NOT NULL REFERENCES recovery_cases (id),
    diagnosis_category  TEXT NOT NULL,
    diagnosis_confidence NUMERIC(5, 4),
    diagnosis_raw       TEXT,
    strategy            TEXT NOT NULL,
    decided_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_recovery_decisions_case_id ON recovery_decisions (recovery_case_id);

CREATE TABLE recovery_actions (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recovery_decision_id BIGINT NOT NULL REFERENCES recovery_decisions (id),
    action_type          TEXT NOT NULL,
    policy_result        TEXT NOT NULL,
    execution_status     TEXT NOT NULL DEFAULT 'PENDING',
    executed_at          TIMESTAMPTZ,
    verification_status  TEXT NOT NULL DEFAULT 'UNVERIFIED',
    verified_at          TIMESTAMPTZ,
    external_reference   TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_recovery_actions_decision_id ON recovery_actions (recovery_decision_id);

CREATE TABLE audit_events (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recovery_case_id  BIGINT NOT NULL REFERENCES recovery_cases (id),
    event_type        TEXT NOT NULL,
    event_payload     JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_events_case_id ON audit_events (recovery_case_id);
