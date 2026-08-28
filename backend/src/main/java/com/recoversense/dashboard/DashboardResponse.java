package com.recoversense.dashboard;

import java.util.List;

public record DashboardResponse(
        DashboardSummary summary,
        List<StrategyMixEntry> strategyMix,
        List<RecentCaseSummary> recentCases
) {
}
