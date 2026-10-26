package com.flowforge.domain.validation;

import java.util.Collection;
import java.util.Optional;

/** Port: the set of node types this backend supports. */
public interface NodeTypeRegistry {

    Optional<NodeTypeDefinition> find(String type);

    Collection<NodeTypeDefinition> all();
}
