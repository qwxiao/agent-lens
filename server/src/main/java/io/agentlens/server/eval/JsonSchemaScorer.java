package io.agentlens.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.StringJoiner;

/**
 * Passes when the output parses as JSON (markdown fences tolerated) and conforms to
 * the case's JSON Schema, draft 2020-12 (ADR 0004).
 */
@Component
public class JsonSchemaScorer implements Scorer {

    public static final String NAME = "json_schema";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Score score(DatasetCase testCase, String output) {
        if (testCase.jsonSchema() == null || testCase.jsonSchema().isNull()) {
            return new Score(null, "skipped: case has no json_schema");
        }
        JsonNode actual;
        try {
            actual = MAPPER.readTree(FencedJson.strip(output));
        } catch (IOException e) {
            return new Score(false, "output is not valid JSON: " + e.getMessage());
        }
        JsonSchema schema = factory.getSchema(testCase.jsonSchema());
        List<ValidationMessage> errors = schema.validate(actual).stream().toList();
        if (errors.isEmpty()) {
            return new Score(true, "conforms to json_schema");
        }
        StringJoiner details = new StringJoiner("; ");
        errors.stream().limit(3).forEach(error -> details.add(error.getMessage()));
        if (errors.size() > 3) {
            details.add(errors.size() - 3 + " more");
        }
        return new Score(false, details.toString());
    }
}
