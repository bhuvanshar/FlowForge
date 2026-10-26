package com.flowforge.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;

/** Loads the published workflow JSON Schema from the main classpath for contract assertions in tests. */
public final class WorkflowSchema {

    private static final JsonSchema SCHEMA = load();

    private WorkflowSchema() {
    }

    public static Set<ValidationMessage> validate(JsonNode json) {
        return SCHEMA.validate(json);
    }

    public static InputStream exampleDefinition() {
        return resource("/workflow/example-definition.v1.json");
    }

    private static JsonSchema load() {
        try (InputStream in = resource("/schemas/workflow-definition.v1.schema.json")) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream resource(String path) {
        InputStream in = WorkflowSchema.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource " + path);
        }
        return in;
    }
}
