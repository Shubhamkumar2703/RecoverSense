-- Resets ONLY the hero demo case (pay_demo_payment_link, cust_demo_payment_link)
-- back to its DemoDataSeeder-seeded FAILED state, so the "Recover -> Payment
-- Link -> EXECUTED_AWAITING_VERIFICATION -> Verify -> RECOVERED" walkthrough
-- can be re-run from the top locally.
--
-- Deletes only this payment's recovery pipeline rows (audit_events ->
-- recovery_actions -> recovery_decisions -> recovery_cases, in FK order) and
-- restores the payments row. Touches no other payment/customer, no
-- migrations, no application code. Safe to rerun (no-ops if the payment is
-- missing or already FAILED with no open case).
--
-- Usage (local dev DB from backend/src/main/resources/application.properties):
--   psql -h localhost -p 5433 -U recoversense -d recoversense -f scripts/reset-demo-payment-link.sql

DO $$
DECLARE
    v_payment_id BIGINT;
BEGIN
    SELECT id INTO v_payment_id FROM payments WHERE external_payment_id = 'pay_demo_payment_link';

    IF v_payment_id IS NULL THEN
        RAISE NOTICE 'pay_demo_payment_link not found - nothing to reset';
        RETURN;
    END IF;

    DELETE FROM audit_events WHERE recovery_case_id IN (SELECT id FROM recovery_cases WHERE payment_id = v_payment_id);
    DELETE FROM recovery_actions WHERE recovery_case_id IN (SELECT id FROM recovery_cases WHERE payment_id = v_payment_id);
    DELETE FROM recovery_decisions WHERE recovery_case_id IN (SELECT id FROM recovery_cases WHERE payment_id = v_payment_id);
    DELETE FROM recovery_cases WHERE payment_id = v_payment_id;

    UPDATE payments
    SET status = 'FAILED',
        failure_reason = 'Repeated payment failure - card declined multiple times',
        failed_at = now() - interval '30 minutes'
    WHERE id = v_payment_id;

    RAISE NOTICE 'pay_demo_payment_link reset to FAILED';
END $$;
