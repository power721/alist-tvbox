package cn.har01d.atmaintenance.dto;

public record AtRuntimeActivation(String id, String requestKey, String state,
                                  String loadedGeneration, String errorCode) {}
