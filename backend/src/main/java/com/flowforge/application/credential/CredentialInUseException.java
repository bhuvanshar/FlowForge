package com.flowforge.application.credential;

import com.flowforge.application.ConflictException;
import java.util.List;

/** The credential is referenced by workflows and cannot be deleted (409 with the list). */
public class CredentialInUseException extends ConflictException {

    public record Usage(String id, String name, String status) {
    }

    private final List<Usage> workflows;

    public CredentialInUseException(List<Usage> workflows) {
        super("The credential is used by " + workflows.size() + " workflow(s). Remove it from them first.");
        this.workflows = List.copyOf(workflows);
    }

    public List<Usage> workflows() {
        return workflows;
    }
}
