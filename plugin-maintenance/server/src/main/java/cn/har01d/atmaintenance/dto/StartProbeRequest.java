package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.Pattern;

public record StartProbeRequest(@Pattern(regexp = "static|offline_fixture|container_fixture|managed_source") String mode) {
}
