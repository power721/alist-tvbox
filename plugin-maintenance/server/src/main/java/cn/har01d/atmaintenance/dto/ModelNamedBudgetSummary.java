package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ModelNamedBudgetSummary(String modelName, LocalDate budgetDay, int usedCalls,
                                      long reservedInputBytes, long reservedOutputTokens,
                                      int dailyCallLimit, long dailyInputBytes, long dailyOutputTokens,
                                      int reportedUsageCalls, int pricedCalls, int callsWithoutUsage,
                                      int usageCallsWithoutPrice, Long promptTokens, Long completionTokens,
                                      Long totalTokens, String estimatedCostUsd,
                                      @JsonSerialize(using = ToStringSerializer.class) BigDecimal reservedCostUsd,
                                      @JsonSerialize(using = ToStringSerializer.class) BigDecimal dailyCostUsd) {
}
