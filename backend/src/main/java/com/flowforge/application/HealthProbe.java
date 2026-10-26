package com.flowforge.application;

import com.flowforge.domain.ComponentStatus;

/**
 * Port for checking the health of one system component. Each implementation reports a stable
 * component name, so new components (e.g. execution engine) can be added without changing callers.
 */
public interface HealthProbe {

    String componentName();

    ComponentStatus check();
}
