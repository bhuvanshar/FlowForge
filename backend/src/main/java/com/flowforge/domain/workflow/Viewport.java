package com.flowforge.domain.workflow;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/** Editor viewport (pan and zoom) saved with the workflow. */
public record Viewport(double x, double y, @DecimalMin("0.05") @DecimalMax("10") double zoom) {
}
