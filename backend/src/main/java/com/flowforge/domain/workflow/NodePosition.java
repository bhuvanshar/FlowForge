package com.flowforge.domain.workflow;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/** Editor canvas coordinates of a node. Has no effect on execution. */
public record NodePosition(
        @DecimalMin("-1000000") @DecimalMax("1000000") double x,
        @DecimalMin("-1000000") @DecimalMax("1000000") double y) {
}
