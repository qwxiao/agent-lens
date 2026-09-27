package io.agentlens.server.query;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentlens.server.cost.CostCalculator;
import io.agentlens.server.cost.CostCalculator.TokenUsage;
import io.agentlens.server.store.SpanRecord;
import io.agentlens.server.store.SpanStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Groups stored spans back into traces and decorates them with LLM/tool
 * classification and cost, computed at query time against the current price list.
 */
@Service
public class TraceQueryService {

    private final SpanStore store;
    private final CostCalculator cost;

    public TraceQueryService(SpanStore store, CostCalculator cost) {
        this.store = store;
        this.cost = cost;
    }

    public List<TraceSummary> listTraces(int limit, int offset) {
        List<String> traceIds = store.recentTraceIds(limit, offset);
        Map<String, List<SpanRecord>> byTrace = store.findByTraceIds(traceIds).stream()
                .collect(Collectors.groupingBy(SpanRecord::traceId));
        List<TraceSummary> summaries = new ArrayList<>();
        for (String traceId : traceIds) {
            List<SpanRecord> spans = byTrace.get(traceId);
            if (spans != null) {
                summaries.add(summarize(traceId, spans));
            }
        }
        return summaries;
    }

    public long countTraces() {
        return store.countTraces();
    }

    public Optional<TraceDetail> traceDetail(String traceId) {
        List<SpanRecord> spans = store.findByTraceIds(List.of(traceId));
        if (spans.isEmpty()) {
            return Optional.empty();
        }
        TraceSummary summary = summarize(traceId, spans);
        List<SpanView> views = spans.stream().map(this::toView).toList();
        return Optional.of(new TraceDetail(summary.traceId(), summary.startTime(), summary.endTime(),
                summary.durationMs(), summary.spanCount(), summary.services(), summary.models(),
                summary.inputTokens(), summary.outputTokens(), summary.costUsd(),
                summary.rootSpanName(), views));
    }

    private TraceSummary summarize(String traceId, List<SpanRecord> spans) {
        List<SpanRecord> sorted = spans.stream()
                .sorted(java.util.Comparator.comparing(SpanRecord::startTime))
                .toList();
        Instant start = sorted.get(0).startTime();
        Instant end = sorted.stream().map(SpanRecord::endTime).max(Instant::compareTo).orElse(start);

        Set<String> services = new TreeSet<>();
        Set<String> models = new TreeSet<>();
        long inputTokens = 0;
        long outputTokens = 0;
        BigDecimal total = BigDecimal.ZERO;
        boolean costKnown = false;
        for (SpanRecord span : sorted) {
            if (span.serviceName() != null) {
                services.add(span.serviceName());
            }
            String model = cost.modelOf(span.attributes());
            if (model != null) {
                models.add(model);
            }
            TokenUsage usage = cost.usageOf(span.attributes());
            if (usage != null) {
                inputTokens += usage.inputTokens();
                outputTokens += usage.outputTokens();
            }
            BigDecimal spanCost = cost.costOf(span.attributes());
            if (spanCost != null) {
                total = total.add(spanCost);
                costKnown = true;
            }
        }
        return new TraceSummary(traceId, start, end, Duration.between(start, end).toMillis(),
                sorted.size(), List.copyOf(services), List.copyOf(models),
                inputTokens, outputTokens, costKnown ? total : null, rootNameOf(sorted));
    }

    private static String rootNameOf(List<SpanRecord> sorted) {
        return sorted.stream()
                .filter(span -> span.parentSpanId() == null)
                .map(SpanRecord::name)
                .findFirst()
                .orElse(sorted.get(0).name());
    }

    private SpanView toView(SpanRecord span) {
        JsonNode attributes = span.attributes();
        TokenUsage usage = cost.usageOf(attributes);
        return new SpanView(span.spanId(), span.parentSpanId(), span.name(), span.spanKind(),
                span.serviceName(), span.startTime(), Duration.between(span.startTime(), span.endTime()).toMillis(),
                categoryOf(span), span.statusCode(), cost.modelOf(attributes),
                textOf(attributes, "gen_ai.system"), textOf(attributes, "gen_ai.tool.name"),
                usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens(),
                cost.costOf(attributes), attributes);
    }

    private String categoryOf(SpanRecord span) {
        JsonNode attrs = span.attributes();
        if (hasAny(attrs, "gen_ai.usage.input_tokens", "gen_ai.usage.output_tokens",
                "gen_ai.usage.prompt_tokens", "gen_ai.usage.completion_tokens")
                || hasAny(attrs, "gen_ai.request.model", "gen_ai.response.model")
                || matchesOperation(attrs, "chat", "text_completion", "generate_content", "embeddings")) {
            return "llm";
        }
        if (hasAny(attrs, "gen_ai.tool.name", "gen_ai.tool.call.id")
                || matchesOperation(attrs, "execute_tool")) {
            return "tool";
        }
        return "span";
    }

    private static boolean matchesOperation(JsonNode attrs, String... operations) {
        String operation = textOf(attrs, "gen_ai.operation.name");
        if (operation == null) {
            return false;
        }
        for (String candidate : operations) {
            if (operation.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAny(JsonNode attrs, String... keys) {
        for (String key : keys) {
            JsonNode node = attrs.get(key);
            if (node != null && !node.isNull()) {
                return true;
            }
        }
        return false;
    }

    private static String textOf(JsonNode attrs, String key) {
        JsonNode node = attrs == null ? null : attrs.get(key);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }
}
