-- The hosted Payment Link URL (Razorpay's short_url) was previously
-- request-scoped only (RecoveryAction.providerUrl was @Transient) - an
-- operator who didn't act on the link in the same HTTP response that
-- created it had no way to retrieve it again. Nullable: every existing row
-- and every non-PAYMENT_LINK action legitimately has none.
ALTER TABLE recovery_actions ADD COLUMN provider_url TEXT;
