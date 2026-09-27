package io.agentlens.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentlens.server.store.DatasetStore;
import io.agentlens.server.store.RunStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executes evaluation runs asynchronously: replay every dataset case against the
 * target agent, score the outputs and persist each result as it completes, so the
 * API can report live progress while the run is in flight (ADR 0004).
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunStore runs;
    private final DatasetStore datasets;
    private final TargetAgentClient client;
    private final Map<String, Scorer> scorers = new LinkedHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public EvalRunner(RunStore runs, DatasetStore datasets, TargetAgentClient client, List<Scorer> scorers) {
        this.runs = runs;
        this.datasets = datasets;
        this.client = client;
        scorers.forEach(scorer -> this.scorers.put(scorer.name(), scorer));
    }

    public record StartRequest(String datasetId, String name, String targetUrl, List<String> scorers) {
    }

    /** Creates the run row and returns immediately; execution continues on a virtual thread. */
    public EvalRun start(StartRequest request) {
        Dataset dataset = datasets.get(request.datasetId())
                .orElseThrow(() -> new NoSuchElementException("dataset not found: " + request.datasetId()));
        String name = request.name() == null || request.name().isBlank()
                ? dataset.name() + " " + Instant.now().toString().substring(0, 16)
                : request.name();
        EvalRun run = new EvalRun(UUID.randomUUID().toString(), dataset.id(), name, "running",
                request.targetUrl(), scorerArray(request.scorers()), Instant.now(), null);
        runs.create(run);
        executor.submit(() -> execute(run));
        return run;
    }

    private static JsonNode scorerArray(List<String> names) {
        var array = MAPPER.createArrayNode();
        if (names != null) {
            names.forEach(array::add);
        }
        return array;
    }

    private void execute(EvalRun run) {
        try {
            List<String> scorerNames = run.scorers() == null ? List.of()
                    : streamOf(run.scorers()).toList();
            for (DatasetCase testCase : datasets.listCases(run.datasetId())) {
                runs.insertResult(runCase(run, testCase, scorerNames));
            }
            runs.updateStatus(run.id(), "completed", Instant.now());
        } catch (Exception e) {
            log.error("eval run {} failed", run.id(), e);
            runs.updateStatus(run.id(), "failed", Instant.now());
        }
    }

    private static java.util.stream.Stream<String> streamOf(JsonNode array) {
        var builder = java.util.stream.Stream.<String>builder();
        array.forEach(node -> builder.add(node.asText()));
        return builder.build();
    }

    private RunCaseResult runCase(EvalRun run, DatasetCase testCase, List<String> scorerNames) {
        long startedAt = System.nanoTime();
        TargetAgentClient.ReplayResult replay = client.replay(run, testCase);
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        if (replay.error() != null) {
            return new RunCaseResult(run.id(), testCase.id(), testCase.input(), null,
                    replay.error(), latencyMs, false, MAPPER.createObjectNode(), null, Instant.now());
        }
        boolean passed = true;
        ObjectNode scores = MAPPER.createObjectNode();
        for (String scorerName : scorerNames) {
            Scorer scorer = scorers.get(scorerName);
            Scorer.Score score = scorer == null
                    ? new Scorer.Score(false, "unknown scorer: " + scorerName)
                    : scorer.score(testCase, replay.output());
            scores.set(scorerName, MAPPER.valueToTree(score));
            if (score.passed() != null) {
                passed &= score.passed();
            }
        }
        return new RunCaseResult(run.id(), testCase.id(), testCase.input(), replay.output(), null,
                latencyMs, passed, scores, replay.traceId(), Instant.now());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
