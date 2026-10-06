package process.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.io.IOException;

/** MIG-214: the hourly sweep also clears what a crash left on the replica's disk, and a failure there stops nothing. */
class DatasetSweepLeftoversTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T06:00:00Z"), ZoneOffset.UTC);

    @Test
    void theSweepAsksTheStoreForLeftoversAnHourOld() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object.class))).thenReturn(Collections.emptyList());
        DatasetStore store = mock(DatasetStore.class);

        new DatasetSweep(jdbc, store, CLOCK).sweep();

        verify(store).sweepLeftovers(Duration.ofHours(1));
    }

    @Test
    void aLeftoverSweepThatFailsDoesNotFailTheExpirySweep() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object.class))).thenReturn(Collections.emptyList());
        DatasetStore store = mock(DatasetStore.class);
        when(store.sweepLeftovers(any())).thenThrow(new IOException("disk gone"));

        assertThat(new DatasetSweep(jdbc, store, CLOCK).sweep()).isZero();
    }
}
