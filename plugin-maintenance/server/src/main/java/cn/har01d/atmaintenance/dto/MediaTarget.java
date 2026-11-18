package cn.har01d.atmaintenance.dto;

import java.net.URI;
import java.util.Map;

/** Ephemeral transport input. Never persist or serialize this object. */
public record MediaTarget(URI uri, Map<String, String> headers) {
    @Override
    public String toString() { return "MediaTarget[redacted]"; }
}
