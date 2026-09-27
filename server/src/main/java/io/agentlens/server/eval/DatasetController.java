package io.agentlens.server.eval;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Dataset management API: CRUD plus NDJSON case import/export and trace snapshotting. */
@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    public record CreateRequest(String name, String description) {
    }

    private final DatasetService service;

    public DatasetController(DatasetService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Dataset> create(@RequestBody CreateRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        Dataset dataset = service.create(request.name(), request.description());
        return ResponseEntity.created(URI.create("/api/datasets/" + dataset.id())).body(dataset);
    }

    @GetMapping
    public List<DatasetSummary> list() {
        return service.listSummaries();
    }

    @GetMapping("/{id}")
    public Dataset get(@PathVariable String id) {
        return service.get(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Case import in NDJSON; each line is one case object. */
    @PutMapping(value = "/{id}/cases", consumes = MediaType.APPLICATION_NDJSON_VALUE)
    public Map<String, Integer> importCases(@PathVariable String id, @RequestBody String ndjson) {
        return Map.of("upserted", service.importJsonl(id, ndjson));
    }

    @GetMapping(value = "/{id}/cases", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public String exportCases(@PathVariable String id) {
        return service.exportJsonl(id);
    }

    @PostMapping("/{id}/cases/from-trace/{traceId}")
    public ResponseEntity<DatasetCase> caseFromTrace(@PathVariable String id, @PathVariable String traceId) {
        DatasetCase testCase = service.caseFromTrace(id, traceId);
        return ResponseEntity.created(URI.create("/api/datasets/" + id + "/cases")).body(testCase);
    }
}
