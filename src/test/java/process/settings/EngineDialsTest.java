package process.settings;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Owner #24: what the engine reads -- a workspace's own value where the dial allows one, else the platform's, else the
 * environment's default, else the built-in; every value clamped into its range; read once and cached until a change.
 */
class EngineDialsTest {

    @Test
    void theEnvironmentSetsTheDefaultAndTheRangeHoldsIt() {
        Environment env = mock(Environment.class);
        when(env.getProperty("process.pipeline.run-memory-mb", Long.class)).thenReturn(256L);
        when(env.getProperty("process.pipeline.max-steps", Long.class)).thenReturn(9999L);
        EngineDials dials = new EngineDials(null, env);

        assertThat(dials.defaultOf(EngineDial.RUN_MEMORY_MB)).isEqualTo(256L);
        assertThat(dials.defaultOf(EngineDial.PIPELINE_MAX_STEPS)).isEqualTo(500L);
        assertThat(dials.defaultOf(EngineDial.QUEUE_FETCH_LIMIT)).isEqualTo(1000L);
        assertThat(dials.forWorkspace(EngineDial.RUN_MEMORY_MB, 2960L)).isEqualTo(256L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWorkspacesOwnValueWinsThenThePlatformsThenTheDefaultAndItIsCached() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(call -> {
            RowCallbackHandler handler = call.getArgument(1);
            for (String[] row : new String[][]{{"RUN_MEMORY_MB", "256"}, {"BATCH_ROWS", "99999999"}, {"SCHEDULER_LAST_RUN_TIME", "2026-01-01"},
                {"PIPELINE_MAX_STEPS", "not a number"}}) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString(1)).thenReturn(row[0]);
                when(rs.getString(2)).thenReturn(row[1]);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(eq("SELECT setting_key, setting_value FROM orchestration_setting"), any(RowCallbackHandler.class));
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(Arrays.asList(
            new Object[]{2960L, "RUN_MEMORY_MB", "1024"}, new Object[]{2960L, "ENGINE_THREADS", "32"}, new Object[]{2961L, "RUN_MEMORY_MB", "8"}));
        EngineDials dials = new EngineDials(jdbc, null);

        assertThat(dials.forWorkspace(EngineDial.RUN_MEMORY_MB, 2960L)).as("its own").isEqualTo(1024L);
        assertThat(dials.forWorkspace(EngineDial.RUN_MEMORY_MB, 2961L)).as("its own, clamped").isEqualTo(16L);
        assertThat(dials.forWorkspace(EngineDial.RUN_MEMORY_MB, 2962L)).as("the platform's").isEqualTo(256L);
        assertThat(dials.forWorkspace(EngineDial.ENGINE_THREADS, 2960L)).as("no override on a shared pool").isEqualTo(4L);
        assertThat(dials.platform(EngineDial.BATCH_ROWS)).as("clamped").isEqualTo(100_000L);
        assertThat(dials.platform(EngineDial.PIPELINE_MAX_STEPS)).as("unreadable: the default").isEqualTo(100L);
        assertThat(dials.storedPlatform(EngineDial.QUEUE_FETCH_LIMIT)).isNull();
        verify(jdbc, times(1)).query(eq("SELECT setting_key, setting_value FROM orchestration_setting"), any(RowCallbackHandler.class));

        dials.invalidate();
        dials.platform(EngineDial.BATCH_ROWS);
        verify(jdbc, times(2)).query(eq("SELECT setting_key, setting_value FROM orchestration_setting"), any(RowCallbackHandler.class));
    }

    @Test
    void aFailedReadKeepsTheDefaults() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(call -> { throw new IllegalStateException("down"); }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        EngineDials dials = new EngineDials(jdbc, null);

        assertThat(dials.platform(EngineDial.RUN_MEMORY_MB)).isEqualTo(128L);
        assertThat(dials.overrides()).isEqualTo(Collections.emptyMap());
    }
}
