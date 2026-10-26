package com.flowforge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowforge.domain.ComponentStatus;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DatabaseHealthProbeTest {

    @Test
    void upWhenConnectionValid() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.isValid(2)).thenReturn(true);

        assertThat(new DatabaseHealthProbe(ds).check()).isEqualTo(ComponentStatus.UP);
    }

    @Test
    void downWhenConnectionFails() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("refused"));

        assertThat(new DatabaseHealthProbe(ds).check()).isEqualTo(ComponentStatus.DOWN);
    }
}
