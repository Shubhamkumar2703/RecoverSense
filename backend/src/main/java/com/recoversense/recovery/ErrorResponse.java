package com.recoversense.recovery;

/**
 * Safe, minimal error body for RecoveryController - always a plain message
 * derived from a known, already-safe exception message (see the exceptions
 * this is built from: none of them ever carries a provider credential or a
 * raw Razorpay/Claude response body). Never a stack trace, never an
 * exception class name, never a raw provider error.
 */
record ErrorResponse(String message) {
}
