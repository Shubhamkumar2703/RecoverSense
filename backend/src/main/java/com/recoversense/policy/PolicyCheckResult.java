package com.recoversense.policy;

public record PolicyCheckResult(String checkName, boolean passed, String reason) {
}
