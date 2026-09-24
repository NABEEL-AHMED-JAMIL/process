package process.config;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import process.api.KafkaConnectionProfileRestApi;
import process.api.SourceJobRestApi;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobDto;
import process.model.service.KafkaConnectionProfileService;
import process.model.service.SourceJobService;
import process.model.service.impl.KafkaConnectionProfileServiceImpl;
import process.util.ProcessUtil;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-71: the database now refuses a second scheduler for a job and a second default Kafka connection for
 * a tenant or the platform. The losing request of a race is told so in words -- a 409 with a refusal it can
 * act on -- not handed a 500 (the translation DEF-008 asks of billing, applied here).
 */
class ConstraintRefusalTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static DataIntegrityViolationException violating(String constraint) {
        SQLException cause = new SQLException("ERROR: duplicate key value violates unique constraint \"" + constraint + "\"\n"
            + "  Detail: Key (job_id)=(7101) already exists.", "23505");
        return new DataIntegrityViolationException("could not execute statement; constraint [" + constraint + "]", cause);
    }

    @Test
    void aSecondSchedulerForAJobIsARefusal() {
        ResponseEntity<ResponseDto> answer = this.handler.handleIntegrityViolation(violating("uk_scheduler_job_id"));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(answer.getBody().getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        assertThat(answer.getBody().getMessage()).contains("already has a schedule").doesNotContain("7101").doesNotContain("duplicate key");
    }

    @Test
    void aSecondDefaultKafkaConnectionIsARefusal() {
        for (String constraint : new String[] {"ux_kcp_one_default_per_tenant", "ux_kcp_one_platform_default"}) {
            ResponseEntity<ResponseDto> answer = this.handler.handleIntegrityViolation(violating(constraint));

            assertThat(answer.getStatusCode()).as(constraint).isEqualTo(HttpStatus.CONFLICT);
            assertThat(answer.getBody().getMessage()).as(constraint).contains("default Kafka connection");
        }
    }

    /** Hibernate names the constraint in brackets; the driver, reached through JdbcTemplate, in quotes. Either is enough. */
    @Test
    void theConstraintIsFoundWhicheverLayerNamesIt() {
        DataIntegrityViolationException hibernate = new DataIntegrityViolationException(
            "could not execute statement; SQL [n/a]; constraint [uk_scheduler_job_id]");
        DataIntegrityViolationException driver = new DataIntegrityViolationException("PreparedStatementCallback",
            new SQLException("ERROR: duplicate key value violates unique constraint \"uk_scheduler_job_id\"", "23505"));

        assertThat(this.handler.handleIntegrityViolation(hibernate).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(this.handler.handleIntegrityViolation(driver).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anyOtherViolationStaysAnOpaque500() {
        ResponseEntity<ResponseDto> answer = this.handler.handleIntegrityViolation(violating("some_other_key"));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(answer.getBody().getMessage()).isEqualTo(ProcessUtil.INTERNAL_ERROR_500);
    }

    /** The endpoints that can lose these races catch everything, so the refusal has to be made there too. */
    @Test
    void theEndpointsThatCanLoseTheRaceAnswer409() throws Exception {
        KafkaConnectionProfileService profiles = mock(KafkaConnectionProfileService.class);
        when(profiles.setAsDefault(7102L)).thenThrow(violating("ux_kcp_one_default_per_tenant"));
        SourceJobService jobs = mock(SourceJobService.class);
        SourceJobDto job = new SourceJobDto();
        when(jobs.updateSourceJob(job)).thenThrow(violating("uk_scheduler_job_id"));
        when(jobs.addSourceJob(job)).thenThrow(new IllegalStateException("anything else"));

        ResponseEntity<?> promoted = new KafkaConnectionProfileRestApi(profiles).setAsDefault(7102L);
        ResponseEntity<?> updated = new SourceJobRestApi(jobs, null, null).updateSourceJob(job);
        ResponseEntity<?> added = new SourceJobRestApi(jobs, null, null).addSourceJob(job);

        assertThat(promoted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((ResponseDto) promoted.getBody()).getMessage()).contains("default Kafka connection");
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((ResponseDto) updated.getBody()).getMessage()).contains("already has a schedule");
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(((ResponseDto) added.getBody()).getMessage()).isEqualTo(ProcessUtil.INTERNAL_ERROR_500);
    }

    /** Demote and promote in one transaction: the index makes a half-done setAsDefault fail loudly. */
    @Test
    void setAsDefaultDemotesAndPromotesInOneTransaction() throws Exception {
        assertThat(KafkaConnectionProfileServiceImpl.class.getMethod("setAsDefault", Long.class).getAnnotation(Transactional.class))
            .isNotNull();
    }
}
