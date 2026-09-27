package io.agentlens.server.cost;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Model price list loaded once at startup from a YAML file. A model id is matched
 * exactly first, then by the longest matching prefix, so dated variants
 * ("gpt-4o-2024-08-06") resolve to their family price. Provider prefixes
 * ("openai/gpt-4o") are stripped before matching.
 */
@Component
public class ModelPriceTable {

    private final List<ModelPrice> prices;

    public ModelPriceTable(
            @Value("${agentlens.cost.price-file:classpath:model-prices.yaml}") String priceFile) {
        Resource resource = new DefaultResourceLoader().getResource(priceFile);
        try (InputStream in = resource.getInputStream()) {
            this.prices = load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load model price file: " + priceFile, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ModelPrice> load(InputStream in) throws IOException {
        Object root = new Yaml().load(in);
        if (!(root instanceof Map<?, ?> map) || !(map.get("prices") instanceof List<?> entries)) {
            return List.of();
        }
        List<ModelPrice> out = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> m && m.get("model") instanceof String model
                    && m.get("input-per-1m") != null && m.get("output-per-1m") != null) {
                out.add(new ModelPrice(model, decimal(m.get("input-per-1m")), decimal(m.get("output-per-1m"))));
            }
        }
        out.sort(Comparator.comparingInt((ModelPrice p) -> p.model().length()).reversed());
        return List.copyOf(out);
    }

    private static BigDecimal decimal(Object raw) {
        return raw instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : new BigDecimal(String.valueOf(raw));
    }

    /** @return the matching price, or null when the model is unknown */
    public ModelPrice match(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        int lastSlash = model.lastIndexOf('/');
        if (lastSlash >= 0) {
            model = model.substring(lastSlash + 1);
        }
        for (ModelPrice price : prices) {
            if (model.equals(price.model()) || model.startsWith(price.model())) {
                return price;
            }
        }
        return null;
    }
}
