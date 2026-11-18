package cn.har01d.atmaintenance.dto;

import java.util.List;
import java.util.Map;

public record ExtractionRule(String rowSelector, Map<String, ExtractionField> fields, List<String> requiredFields) {}
