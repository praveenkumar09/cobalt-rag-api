package com.cobalt.rag.model;

/** A program's complete source, reconstructed from its ingested chunks — never fabricated. */
public record ProgramSource(String programId, String sourceFile, String content) {
}
