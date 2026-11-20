package cn.har01d.atmaintenance.dto;

import java.net.URI;
import java.net.http.HttpHeaders;

/** Ephemeral HTTP values; persisted evidence uses opaque resource IDs. */
public record MediaHttpResult(URI uri, int status, HttpHeaders headers, byte[] body,
                              boolean truncated, String resourceId) {
    @Override
    public String toString() { return "MediaHttpResult[redacted]"; }
}
