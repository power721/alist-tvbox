package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ModelBudgetSummary(LocalDate budgetDay, int usedCalls, long reservedInputBytes,
                                 long reservedOutputTokens,
                                 @JsonSerialize(using = ToStringSerializer.class) BigDecimal reservedCostUsd,
                                 String activeCallId,
                                 @JsonSerialize(using = ToStringSerializer.class) BigDecimal dailyCostUsd,
                                 LocalDate costBreachDay) {
}
