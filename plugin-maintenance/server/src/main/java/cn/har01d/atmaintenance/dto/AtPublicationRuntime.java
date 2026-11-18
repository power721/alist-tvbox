package cn.har01d.atmaintenance.dto;

public record AtPublicationRuntime(String state, String reason, boolean activationAvailable,
                                   AtSavedRuntimeTarget target) {}
