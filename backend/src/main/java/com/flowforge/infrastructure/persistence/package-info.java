/**
 * JPA entities and Spring Data repositories. Entities are persistence models only: they are never returned from
 * controllers. Aggregates reference each other by id (no JPA associations) to keep loading explicit; referential
 * integrity and cascades are enforced by database foreign keys.
 */
package com.flowforge.infrastructure.persistence;
