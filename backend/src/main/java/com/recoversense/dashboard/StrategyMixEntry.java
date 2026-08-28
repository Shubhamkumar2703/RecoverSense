package com.recoversense.dashboard;

/**
 * Distribution of RecoveryDecision.strategy across all recorded decisions
 * (allowed and blocked alike - this is what the engine recommended, not just
 * what executed).
 */
public record StrategyMixEntry(String strategy, long count) {
}
