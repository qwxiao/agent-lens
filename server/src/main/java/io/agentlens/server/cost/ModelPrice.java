package io.agentlens.server.cost;

import java.math.BigDecimal;

/** List price for one model family, USD per 1M tokens. */
public record ModelPrice(String model, BigDecimal inputPer1M, BigDecimal outputPer1M) {
}
