package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.*;

public record RepairEdit(@NotEmpty @Size(max = 32768) String oldText,
                         @NotNull @Size(max = 32768) String newText) {
}
