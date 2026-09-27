package io.agentlens.server.ingest;

import io.agentlens.server.store.SpanRecord;
import io.agentlens.server.store.SpanStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * OTLP/HTTP collector endpoint for span export (JSON encoding). The response body is
 * the empty {@code ExportTraceServiceResponse} object, as the protocol prescribes.
 */
@RestController
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final OtlpTraceRequestParser parser;
    private final SpanStore store;

    public IngestController(OtlpTraceRequestParser parser, SpanStore store) {
        this.parser = parser;
        this.store = store;
    }

    @PostMapping(value = "/v1/traces", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> exportTraces(
            @RequestHeader(value = HttpHeaders.CONTENT_ENCODING, required = false) String contentEncoding,
            @RequestBody(required = false) byte[] body) throws IOException {

        List<SpanRecord> spans = parser.parse(decode(contentEncoding, body));
        int inserted = store.insert(spans);
        log.info("Accepted {} spans, {} new", spans.size(), inserted);
        return ResponseEntity.ok(Map.of());
    }

    private static InputStream decode(String contentEncoding, byte[] body) throws IOException {
        InputStream in = new ByteArrayInputStream(body == null ? new byte[0] : body);
        if (contentEncoding != null && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            return new GZIPInputStream(in);
        }
        return in;
    }
}
