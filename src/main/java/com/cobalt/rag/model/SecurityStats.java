package com.cobalt.rag.model;

import java.util.List;

/** GET /api/admin/security/stats response — counts per violation type plus the most recent events. */
public record SecurityStats(long totalCount, long promptInjectionCount, long piiRequestedCount,
                             long piiProvidedCount, List<SecurityEvent> recent) {
}
