package io.agentlens.server.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CostCalculatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CostCalculator calculator = new CostCalculator(new ModelPriceTable("classpath:model-prices.yaml"));

    @Test
    void pricesTokensWithCurrentAttributeNames() throws Exception {
        var attributes = MAPPER.readTree("""
                {"gen_ai.request.model": "gpt-4o-mini",
                 "gen_ai.usage.input_tokens": 1000000,
                 "gen_ai.usage.output_tokens": 1000000}
                """);

        // 1M input * 0.15 + 1M output * 0.60
        assertThat(calculator.costOf(attributes)).isEqualByComparingTo("0.750000");
    }

    @Test
    void acceptsDeprecatedPromptCompletionAliases() throws Exception {
        var attributes = MAPPER.readTree("""
                {"gen_ai.response.model": "gpt-4o",
                 "gen_ai.usage.prompt_tokens": "1000",
                 "gen_ai.usage.completion_tokens": "500"}
                """);

        assertThat(calculator.usageOf(attributes).inputTokens()).isEqualTo(1000);
        assertThat(calculator.usageOf(attributes).outputTokens()).isEqualTo(500);
        // 1000 * 2.50 / 1M + 500 * 10.00 / 1M = 0.0025 + 0.005
        assertThat(calculator.costOf(attributes)).isEqualByComparingTo("0.007500");
    }

    @Test
    void prefersResponseModelOverRequestModel() throws Exception {
        var attributes = MAPPER.readTree("""
                {"gen_ai.request.model": "gpt-4o",
                 "gen_ai.response.model": "gpt-4o-mini-2024-07-18",
                 "gen_ai.usage.input_tokens": 1000,
                 "gen_ai.usage.output_tokens": 0}
                """);

        assertThat(calculator.modelOf(attributes)).isEqualTo("gpt-4o-mini-2024-07-18");
        assertThat(calculator.costOf(attributes)).isEqualByComparingTo("0.000150");
    }

    @Test
    void unknownModelOrMissingUsageYieldsNullCost() throws Exception {
        var unknownModel = MAPPER.readTree(
                "{\"gen_ai.request.model\": \"nope\", \"gen_ai.usage.input_tokens\": 5}");
        var noUsage = MAPPER.readTree("{\"gen_ai.request.model\": \"gpt-4o\"}");

        assertThat(calculator.costOf(unknownModel)).isNull();
        assertThat(calculator.costOf(noUsage)).isNull();
        assertThat(calculator.costOf(null)).isNull();
    }
}
