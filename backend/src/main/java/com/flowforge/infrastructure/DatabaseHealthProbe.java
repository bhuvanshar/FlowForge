package com.flowforge.infrastructure;

import com.flowforge.application.HealthProbe;
import com.flowforge.domain.ComponentStatus;
import java.sql.Connection;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DatabaseHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthProbe.class);
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public DatabaseHealthProbe(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String componentName() {
        return "database";
    }

    @Override
    public ComponentStatus check() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS) ? ComponentStatus.UP : ComponentStatus.DOWN;
        } catch (Exception e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return ComponentStatus.DOWN;
        }
    }
}
