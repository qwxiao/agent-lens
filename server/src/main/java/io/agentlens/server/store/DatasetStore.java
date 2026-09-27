package io.agentlens.server.store;

import io.agentlens.server.eval.Dataset;
import io.agentlens.server.eval.DatasetCase;
import io.agentlens.server.eval.DatasetSummary;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for evaluation datasets and their cases (ADR 0004). */
public interface DatasetStore {

    Dataset create(String name, String description);

    List<DatasetSummary> listSummaries();

    Optional<Dataset> get(String id);

    void delete(String id);

    /** Cases are identified by id; existing rows are updated. */
    void upsertCases(List<DatasetCase> cases);

    List<DatasetCase> listCases(String datasetId);
}
