package io.agentlens.server.query;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Read-side API backing the dashboard. */
@RestController
@RequestMapping("/api")
public class TraceQueryController {

    private final TraceQueryService service;

    public TraceQueryController(TraceQueryService service) {
        this.service = service;
    }

    @GetMapping("/traces")
    public Map<String, Object> traces(@RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        int cappedOffset = Math.max(offset, 0);
        return Map.of(
                "traces", service.listTraces(cappedLimit, cappedOffset),
                "total", service.countTraces());
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<TraceDetail> trace(@PathVariable String traceId) {
        return service.traceDetail(traceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
