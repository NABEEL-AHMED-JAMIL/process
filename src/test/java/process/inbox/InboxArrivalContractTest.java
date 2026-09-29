package process.inbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-239: Core reads Storage's InboxArrived as Storage writes it. inbox-arrived-v1.json is committed in both
 * repositories: storage-service's InboxPostgresTest holds what its outbox writes to the same field names, and this
 * holds what Core takes from it. A trigger's file pattern is a glob on the file's name.
 */
class InboxArrivalContractTest {

    static String fixture() throws IOException {
        try (InputStream in = InboxArrivalContractTest.class.getResourceAsStream("/inbox-arrived-v1.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theArrivalIsReadFromStoragesEvent() throws Exception {
        InboxArrival arrival = InboxArrival.parse(fixture());

        assertThat(arrival.getEventId()).isEqualTo("6f1c0f3e-8a4b-4d7e-9a53-2b8f1e0c9d11");
        assertThat(arrival.getArrivalId()).isEqualTo("0b8f6a52-3f5e-4c1a-9d7e-5a4b3c2d1e0f");
        assertThat(arrival.getTenantId()).isEqualTo(4597L);
        assertThat(arrival.getAlias()).isEqualTo("acme-inbox");
        assertThat(arrival.getKey()).isEqualTo("intake/2026/09/28/0b8f6a52-3f5e-4c1a-9d7e-5a4b3c2d1e0f-invoices_q3.csv");
        assertThat(arrival.getFileName()).isEqualTo("invoices_q3.csv");
        assertThat(arrival.getBytes()).isEqualTo(2048L);
        assertThat(arrival.getContentType()).isEqualTo("text/csv");
        assertThat(arrival.getTraceId()).isEqualTo("c0ffee00-1234-4abc-9def-000000000001");
    }

    @Test
    void anEventOfAnotherTypeOrWithoutItsFactsIsUnreadable() throws Exception {
        String good = fixture();
        assertThatThrownBy(() -> InboxArrival.parse(good.replace("platform.storage.inbox-arrived.v1", "platform.storage.object-changed.v1")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InboxArrival.parse(good.replace("\"arrivalId\"", "\"somethingElse\"")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InboxArrival.parse(good.replace("\"tenantId\": 4597,\n    \"alias\"", "\"tenantId\": 0,\n    \"alias\"")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InboxArrival.parse(good.replace("intake/2026", "elsewhere/2026")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InboxArrival.parse("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InboxArrival.parse("{}")).isInstanceOf(IllegalArgumentException.class);
    }

    /** The envelope and the payload name the same workspace, or the event is not believed. */
    @Test
    void anEnvelopeNamingAnotherWorkspaceThanItsPayloadIsUnreadable() throws Exception {
        String twoWorkspaces = fixture().replaceFirst("\"tenantId\": 4597", "\"tenantId\": 4598");
        assertThatThrownBy(() -> InboxArrival.parse(twoWorkspaces)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("workspace");
    }

    @ParameterizedTest
    @CsvSource({
        ", invoices_q3.csv, true",
        "'*', invoices_q3.csv, true",
        "*.csv, invoices_q3.csv, true",
        "*.CSV, invoices_q3.csv, true",
        "invoices_*.csv, invoices_q3.csv, true",
        "invoices_??.csv, invoices_q3.csv, true",
        "*.pdf, invoices_q3.csv, false",
        "claims_*, invoices_q3.csv, false",
        "invoices.csv, invoices_q3.csv, false",
        "a+b.csv, a+b.csv, true",
        "a+b.csv, aab.csv, false"
    })
    void aPatternIsAGlobOnTheFileName(String pattern, String fileName, boolean matches) {
        assertThat(InboxTriggers.matches(pattern, fileName)).isEqualTo(matches);
    }

    @ParameterizedTest
    @ValueSource(strings = {"intake/*.csv", "../*.csv", "a\\b.csv"})
    void aPatternNamesAFileNotAFolder(String pattern) {
        assertThatThrownBy(() -> InboxTriggers.validPattern(pattern)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankPatternMeansEveryFile() {
        assertThat(InboxTriggers.validPattern("   ")).isNull();
        assertThat(InboxTriggers.validPattern(null)).isNull();
        assertThat(InboxTriggers.validPattern(" *.csv ")).isEqualTo("*.csv");
    }
}
