package com.flowforge.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.infrastructure.persistence.WorkflowEntity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Ready-made workflows shipped with the application ({@code samples/*.v1.json}). Creating one makes an ordinary DRAFT
 * workflow the user can edit; nothing is executed or activated, and samples never contain credentials.
 */
@Service
public class SampleWorkflowService {

    private static final List<String> FILES = List.of("/samples/ai-review-triage.v1.json");
    private static final Pattern CREDENTIAL_ID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public record Sample(String id, String name, String description) {
    }

    private record Loaded(Sample sample, JsonNode definition) {
    }

    private final Map<String, Loaded> samples = new LinkedHashMap<>();
    private final WorkflowService workflows;
    private final ObjectMapper mapper;

    public SampleWorkflowService(WorkflowService workflows, ObjectMapper mapper) {
        this.workflows = workflows;
        this.mapper = mapper;
        for (String file : FILES) {
            try (InputStream in = SampleWorkflowService.class.getResourceAsStream(file)) {
                if (in == null) {
                    throw new IllegalStateException("Missing sample " + file);
                }
                JsonNode json = mapper.readTree(in);
                Sample sample = new Sample(json.get("id").asText(), json.get("name").asText(),
                        json.get("description").asText());
                samples.put(sample.id(), new Loaded(sample, json.get("definition")));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public List<Sample> list() {
        return samples.values().stream().map(Loaded::sample).toList();
    }

    /**
     * Creates a DRAFT copy of the sample.
     *
     * @param credentialId optional credential to put on the sample's AI Model node(s); only its id is stored
     */
    public WorkflowEntity create(String id, String credentialId) {
        Loaded loaded = samples.get(id);
        if (loaded == null) {
            throw new ResourceNotFoundException("Sample " + id + " not found.");
        }
        if (credentialId != null && !CREDENTIAL_ID.matcher(credentialId).matches()) {
            throw new InvalidRequestException("credentialId must be a credential id.");
        }
        JsonNode copy = loaded.definition().deepCopy();
        if (credentialId != null) {
            for (JsonNode node : copy.get("nodes")) {
                if ("core.aiModel".equals(node.path("type").asText())) {
                    ((ObjectNode) node.get("configuration")).put("credentialId", credentialId);
                }
            }
        }
        WorkflowDefinition definition = mapper.convertValue(copy, WorkflowDefinition.class);
        return workflows.create(loaded.sample().name(), loaded.sample().description(), definition);
    }
}
