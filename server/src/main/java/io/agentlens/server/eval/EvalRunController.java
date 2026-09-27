package io.agentlens.server.eval;

import io.agentlens.server.eval.RunComparisonService.RunComparison;
import io.agentlens.server.eval.RunComparisonService.RunDetail;
import io.agentlens.server.store.RunStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Evaluation run API. Starting a run returns 202 immediately — the runner keeps
 * going on a virtual thread, clients poll the run detail until status settles.
 */
@RestController
@RequestMapping("/api/eval-runs")
public class EvalRunController {

    private final EvalRunner runner;
    private final RunStore runs;
    private final RunComparisonService comparisons;

    public EvalRunController(EvalRunner runner, RunStore runs, RunComparisonService comparisons) {
        this.runner = runner;
        this.runs = runs;
        this.comparisons = comparisons;
    }

    @PostMapping
    public ResponseEntity<EvalRun> start(@RequestBody EvalRunner.StartRequest request) {
        if (request.datasetId() == null || request.datasetId().isBlank()) {
            throw new IllegalArgumentException("datasetId is required");
        }
        if (request.targetUrl() == null || request.targetUrl().isBlank()) {
            throw new IllegalArgumentException("targetUrl is required");
        }
        EvalRun run = runner.start(request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/eval-runs/" + run.id()))
                .body(run);
    }

    @GetMapping
    public List<RunListItem> list(@RequestParam(defaultValue = "50") int limit) {
        return runs.listSummaries(Math.min(Math.max(limit, 1), 200));
    }

    @GetMapping("/{id}")
    public RunDetail detail(@PathVariable String id) {
        return comparisons.detail(id);
    }

    @GetMapping("/{a}/compare/{b}")
    public RunComparison compare(@PathVariable String a, @PathVariable String b) {
        return comparisons.compare(a, b);
    }
}
