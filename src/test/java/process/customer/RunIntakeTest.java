package process.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** MIG-332: the intake file a run started through the API reads, and the API's word for a run's status. */
class RunIntakeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theIntakeIsTheRecordThenTheStartUnderUnderscoredNames() throws Exception {
        RunIntake.Start start = new RunIntake.Start();
        start.record = JSON.readTree("{\"order\":7,\"_reference\":\"the record's own\",\"lines\":[1,2]}");
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("id", "01JFILE00000000000000000000");
        file.put("bucket", "acme-inbox");
        file.put("key", "intake/api/2026/10/06/01JFILE-orders.csv");
        start.files = Collections.singletonList(file);
        start.reference = "po-7";
        start.clientId = "cl_acme";
        start.eventId = 1001L;
        start.eventType = "order.received";
        PipelineCatalogue.Entry pipeline = new PipelineCatalogue.Entry(42L, 2946L, "Orders", "Active", null, null, null);

        JsonNode intake = JSON.readTree(RunIntake.fileOf(pipeline, start, OffsetDateTime.of(2026, 10, 6, 9, 30, 0, 0, ZoneOffset.ofHours(-5))));

        assertThat(intake.get("order").asInt()).isEqualTo(7);
        assertThat(intake.get("lines").size()).isEqualTo(2);
        assertThat(intake.get("_reference").asText()).as("the start's word wins over a field named like it").isEqualTo("po-7");
        assertThat(intake.get("_client_id").asText()).isEqualTo("cl_acme");
        assertThat(intake.get("_received_at").asText()).isEqualTo("2026-10-06T14:30:00Z");
        assertThat(intake.get("_pipeline_id").asText()).isEqualTo("42");
        assertThat(intake.get("_event_id").asText()).isEqualTo("1001");
        assertThat(intake.get("_event_type").asText()).isEqualTo("order.received");
        assertThat(intake.get("_file_ids").asText()).isEqualTo("01JFILE00000000000000000000");
        assertThat(intake.get("_file_bucket").asText()).isEqualTo("acme-inbox");
        assertThat(intake.get("_file_key").asText()).isEqualTo("intake/api/2026/10/06/01JFILE-orders.csv");
        assertThat(intake.get("_files").get(0).get("id").asText()).isEqualTo("01JFILE00000000000000000000");
    }

    @Test
    void aRunsStatusInTheApisWords() {
        assertThat(RunIntake.statusOf(null)).isEqualTo("queued");
        assertThat(RunIntake.statusOf("Queue")).isEqualTo("queued");
        assertThat(RunIntake.statusOf("Start")).isEqualTo("queued");
        assertThat(RunIntake.statusOf("Running")).isEqualTo("running");
        assertThat(RunIntake.statusOf("Completed")).isEqualTo("completed");
        assertThat(RunIntake.statusOf("Failed")).isEqualTo("failed");
        assertThat(RunIntake.statusOf("Skip")).isEqualTo("skipped");
        assertThat(RunIntake.statusOf("Missed")).isEqualTo("skipped");
        assertThat(RunIntake.statusOf("Interrupt")).isEqualTo("interrupted");
    }

    @Test
    void aContractsPathsAreTheRequests() {
        assertThat(InputContracts.pathOf("$.patient.mrn", "record")).isEqualTo("record.patient.mrn");
        assertThat(InputContracts.pathOf("$.images[1].file_key", "data")).isEqualTo("data.images[1].file_key");
        assertThat(InputContracts.pathOf("$", "record")).isEqualTo("record");
        assertThat(InputContracts.pathOf("$[0]", "data")).isEqualTo("data[0]");
        assertThat(InputContracts.pathOf("amount", "record")).isEqualTo("record.amount");
    }
}
