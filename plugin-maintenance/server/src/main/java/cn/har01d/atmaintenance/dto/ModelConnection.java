package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;

public record ModelConnection(boolean enabled, String protocol, String model, String configurationSha256,
                              int timeoutMillis, int maxRequestBytes, int maxResponseBytes,
                              int maxCompletionTokens, int dailyCallLimit,
                              long dailyInputBytes, long dailyOutputTokens, boolean autoSubmitEnabled,
                              int sourceDailyCallLimit, long sourceDailyInputBytes, long sourceDailyOutputTokens,
                              int modelDailyCallLimit, long modelDailyInputBytes, long modelDailyOutputTokens,
                              BigDecimal inputPriceUsdPerMillion, BigDecimal outputPriceUsdPerMillion,
                              @JsonSerialize(using = ToStringSerializer.class) BigDecimal dailyCostUsd,
                              @JsonSerialize(using = ToStringSerializer.class) BigDecimal sourceDailyCostUsd,
                              @JsonSerialize(using = ToStringSerializer.class) BigDecimal modelDailyCostUsd,
                              String inputTokenBound, String maxTokenParameter, String jsonMode,
                              boolean includeStoreParameter) {
}
