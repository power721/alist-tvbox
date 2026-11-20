package cn.har01d.atmaintenance.dto;

public record SourceCredentialSummary(boolean required, boolean configured, String status, String origin,
                                      boolean cookieConfigured, boolean authorizationConfigured,
                                      String revision, String reason) {}
