package io.agentlens.server.cost;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceTableTest {

    private final ModelPriceTable table = new ModelPriceTable("classpath:model-prices.yaml");

    @Test
    void matchesExactModelId() {
        assertThat(table.match("gpt-4o-mini").model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void matchesLongestPrefixForDatedVariants() {
        assertThat(table.match("gpt-4o-2024-08-06").model()).isEqualTo("gpt-4o");
        assertThat(table.match("gpt-4o-mini-2024-07-18").model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void stripsProviderPrefix() {
        assertThat(table.match("openai/gpt-4o-mini").model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void unknownModelHasNoPrice() {
        assertThat(table.match("totally-made-up-model")).isNull();
        assertThat(table.match(null)).isNull();
        assertThat(table.match(" ")).isNull();
    }
}
