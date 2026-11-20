package cn.har01d.atmaintenance.dto;

import cn.har01d.atmaintenance.config.ModelSettings;
import java.math.BigDecimal;

public record ModelLimits(int dailyCallLimit, long dailyInputBytes, long dailyOutputTokens,
                          int sourceDailyCallLimit, long sourceDailyInputBytes, long sourceDailyOutputTokens,
                          int modelDailyCallLimit, long modelDailyInputBytes, long modelDailyOutputTokens,
                          BigDecimal dailyCostUsd, BigDecimal sourceDailyCostUsd, BigDecimal modelDailyCostUsd,
                          BigDecimal inputPriceUsdPerMillion, BigDecimal outputPriceUsdPerMillion,
                          String inputTokenBound) {
    public static ModelLimits from(ModelSettings s) {
        return new ModelLimits(s.getDailyCallLimit(), s.getDailyInputBytes(), s.getDailyOutputTokens(),
                s.getSourceDailyCallLimit(), s.getSourceDailyInputBytes(), s.getSourceDailyOutputTokens(),
                s.getModelDailyCallLimit(), s.getModelDailyInputBytes(), s.getModelDailyOutputTokens(),
                s.getDailyCostUsd(), s.getSourceDailyCostUsd(), s.getModelDailyCostUsd(),
                s.getInputPriceUsdPerMillion(), s.getOutputPriceUsdPerMillion(), s.getInputTokenBound());
    }

    public void apply(ModelSettings s) {
        s.setDailyCallLimit(dailyCallLimit); s.setDailyInputBytes(dailyInputBytes); s.setDailyOutputTokens(dailyOutputTokens);
        s.setSourceDailyCallLimit(sourceDailyCallLimit); s.setSourceDailyInputBytes(sourceDailyInputBytes);
        s.setSourceDailyOutputTokens(sourceDailyOutputTokens);
        s.setModelDailyCallLimit(modelDailyCallLimit); s.setModelDailyInputBytes(modelDailyInputBytes);
        s.setModelDailyOutputTokens(modelDailyOutputTokens);
        s.setDailyCostUsd(dailyCostUsd); s.setSourceDailyCostUsd(sourceDailyCostUsd); s.setModelDailyCostUsd(modelDailyCostUsd);
        s.setInputPriceUsdPerMillion(inputPriceUsdPerMillion); s.setOutputPriceUsdPerMillion(outputPriceUsdPerMillion);
        s.setInputTokenBound(inputTokenBound == null ? "" : inputTokenBound);
    }
}
