package com.flowforge.domain.workflow;

import jakarta.validation.Valid;

/** Editor-only state. Ignored by validation of the executable graph and by execution. */
public record EditorMetadata(@Valid Viewport viewport) {
}
