package com.cobalt.rag.model;

/** A program's real domain/sub-domain tag from ingestion (its most frequent chunk tagging). */
public record DomainTag(String domain, String subDomain) {
}
