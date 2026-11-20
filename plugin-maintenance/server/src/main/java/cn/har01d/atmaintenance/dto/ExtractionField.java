package cn.har01d.atmaintenance.dto;

import java.util.List;

public record ExtractionField(String selector, String attribute, List<String> transforms) {}
