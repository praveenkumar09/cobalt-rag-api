package com.cobalt.rag.model;

import java.util.List;

/** A step-by-step trace of a concrete "what if" scenario through the retrieved decision logic. */
public record ScenarioTrace(List<ScenarioStep> steps, String outcome) {
}
