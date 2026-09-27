package io.agentlens.server.cost;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes per-span LLM cost from {@code gen_ai.*} usage attributes. Accepts both the
 * current attribute names (gen_ai.usage.input_tokens / output_tokens) and the
 * deprecated prompt/completion aliases still emitted by parts of the ecosystem.
 */
@Component
public class CostCalculator {

    public record TokenUsage(long inputTokens, long outputTokens) {
    }

    private final ModelPriceTable prices;

    public CostCalculator(ModelPriceTable prices) {
        this.prices = prices;
    }

    /** The model actually serving the request, falling back to the requested one. */
    public String modelOf(JsonNode attributes) {
        String model = stringOf(attributes, "gen_ai.response.model");
        return model != null ? model : stringOf(attributes, "gen_ai.request.model");
    }

    public TokenUsage usageOf(JsonNode attributes) {
        Long input = firstLong(attributes, "gen_ai.usage.input_tokens", "gen_ai.usage.prompt_tokens");
        Long output = firstLong(attributes, "gen_ai.usage.output_tokens", "gen_ai.usage.completion_tokens");
        if (input == null && output == null) {
            return null;
        }
        return new TokenUsage(input == null ? 0 : input, output == null ? 0 : output);
    }

    /** @return the span cost in USD, or null when the model or its usage is unknown */
    public BigDecimal costOf(JsonNode attributes) {
        ModelPrice price = prices.match(modelOf(attributes));
        if (price == null) {
            return null;
        }
        TokenUsage usage = usageOf(attributes);
        if (usage == null) {
            return null;
        }
        return price.inputPer1M().multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(price.outputPer1M().multiply(BigDecimal.valueOf(usage.outputTokens())))
                .movePointLeft(6)
                .setScale(6, RoundingMode.HALF_UP);
    }

    private Long firstLong(JsonNode attributes, String... keys) {
        if (attributes == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode node = attributes.get(key);
            if (node != null && !node.isNull()) {
                if (node.isNumber()) {
                    return node.longValue();
                }
                try {
                    return Long.parseLong(node.asText().trim());
                } catch (NumberFormatException ignored) {
                    // attribute present but not numeric: treat as absent
                }
            }
        }
        return null;
    }

    private String stringOf(JsonNode attributes, String key) {
        if (attributes == null) {
            return null;
        }
        JsonNode node = attributes.get(key);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }
}
