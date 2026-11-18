package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/** Carries only the broker diagnostic allowlist, never raw transport or source output. */
public final class ManagedExecutionFailure extends IOException {
    private final JsonNode diagnostic;

    public ManagedExecutionFailure(String code, JsonNode diagnostic) {
        super(code);
        this.diagnostic = diagnostic.deepCopy();
    }

    public JsonNode diagnostic() {
        return diagnostic.deepCopy();
    }
}
