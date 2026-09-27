package io.agentlens.server.store;

import io.agentlens.server.eval.EvalRun;
import io.agentlens.server.eval.RunCaseResult;
import io.agentlens.server.eval.RunListItem;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for evaluation runs and their per-case results (ADR 0004). */
public interface RunStore {

    void create(EvalRun run);

    Optional<EvalRun> get(String id);

    List<RunListItem> listSummaries(int limit);

    void updateStatus(String runId, String status, Instant finishedAt);

    void insertResult(RunCaseResult result);

    List<RunCaseResult> results(String runId);
}
