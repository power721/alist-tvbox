package cn.har01d.atmaintenance.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
@ConfigurationProperties("maintenance.model")
@Getter
@Setter
public class ModelSettings {
    private boolean enabled;
    private boolean autoSubmitEnabled;
    private String endpoint = "";
    private String baseUrl = "";
    private String model = "";
    private String keyEnv = "";
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private String apiKey = "";
    private int timeoutMillis = 8000;
    private int maxRequestBytes = 1048576;
    private int maxResponseBytes = 65536;
    private int maxCompletionTokens = 2048;
    private String maxTokenParameter = "max_completion_tokens";
    private String jsonMode = "json_object";
    private boolean includeStoreParameter = true;
    // Zero means unlimited; usage is still recorded at every scope.
    private int dailyCallLimit;
    private long dailyInputBytes;
    private long dailyOutputTokens;
    private int sourceDailyCallLimit;
    private long sourceDailyInputBytes;
    private long sourceDailyOutputTokens;
    private int modelDailyCallLimit;
    private long modelDailyInputBytes;
    private long modelDailyOutputTokens;
    private BigDecimal inputPriceUsdPerMillion;
    private BigDecimal outputPriceUsdPerMillion;
    private BigDecimal dailyCostUsd;
    private BigDecimal sourceDailyCostUsd;
    private BigDecimal modelDailyCostUsd;
    private String inputTokenBound = "";
}
