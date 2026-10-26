package com.flowforge.infrastructure.persistence;

import com.flowforge.domain.workflow.WorkflowStatus;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Composable filters for {@link WorkflowRepository#findAll(Specification, org.springframework.data.domain.Pageable)}. */
public final class WorkflowSpecifications {

    private WorkflowSpecifications() {
    }

    /** Case-insensitive "contains"; {@code %}, {@code _} and {@code \} in the input are matched literally. */
    public static Specification<WorkflowEntity> nameContains(String text) {
        return (root, query, cb) -> {
            if (text == null || text.isBlank()) {
                return cb.conjunction();
            }
            String escaped = text.strip().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            return cb.like(cb.lower(root.get("name")), "%" + escaped + "%", '\\');
        };
    }

    /** Matches any of the given statuses; with none given, hides archived workflows. */
    public static Specification<WorkflowEntity> statusIn(Collection<WorkflowStatus> statuses) {
        return (root, query, cb) -> statuses == null || statuses.isEmpty()
                ? cb.notEqual(root.get("status"), WorkflowStatus.ARCHIVED)
                : root.get("status").in(List.copyOf(statuses));
    }
}
