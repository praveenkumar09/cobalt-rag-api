package com.cobalt.rag.model;

import java.util.List;

/** GET /api/admin/feedback/stats response — totals plus the most recent notes. */
public record FeedbackStats(long totalCount, long last7DaysCount, List<FeedbackEntry> recent) {
}
