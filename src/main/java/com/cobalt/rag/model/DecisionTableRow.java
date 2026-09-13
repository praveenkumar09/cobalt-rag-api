package com.cobalt.rag.model;

/** One row of a business decision table extracted from real conditional code logic. */
public record DecisionTableRow(String condition, String outcome, String exception) {
}
