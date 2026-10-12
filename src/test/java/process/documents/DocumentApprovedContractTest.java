package process.documents;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-364: Core reads Document Intelligence's approval as ai-service writes it (DocumentApproval, MIG-270).
 * document-approved-v1.json is committed in both repositories: ai-service's DocumentApprovalContractTest holds what its
 * outbox writes to the same field names, and this holds what Core takes from it.
 */
class DocumentApprovedContractTest {

    static String fixture() throws IOException {
        try (InputStream in = DocumentApprovedContractTest.class.getResourceAsStream("/document-approved-v1.json")) {
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
    void theApprovalIsReadFromDocumentIntelligencesEvent() throws Exception {
        DocumentApproved approved = DocumentApproved.parse(fixture());

        assertThat(approved.getEventId()).isEqualTo("5d0c6f1e-2b7a-4c9e-8f31-7a6b5c4d3e21");
        assertThat(approved.getTraceId()).isEqualTo("c0ffee00-1234-4abc-9def-000000000364");
        assertThat(approved.getTenantId()).isEqualTo(2961L);
        assertThat(approved.getExtractionId()).isEqualTo(1180L);
        assertThat(approved.getOcrDocumentId()).isEqualTo(2207L);
        assertThat(approved.getDocumentTypeId()).isEqualTo(1007L);
        assertThat(approved.getTypeKey()).isEqualTo("invoice");
        assertThat(approved.getTypeVersion()).isEqualTo(2);
        assertThat(approved.getApproval()).isEqualTo("reviewer");
        assertThat(approved.getApprovedBy()).isEqualTo(4644L);
    }

    @Test
    void anAutomaticApprovalHasNobodyWhoApprovedIt() throws Exception {
        DocumentApproved approved = DocumentApproved.parse(fixture().replace("\"reviewer\"", "\"auto\"").replace("\"approvedBy\": 4644",
            "\"approvedBy\": null"));
        assertThat(approved.getApproval()).isEqualTo("auto");
        assertThat(approved.getApprovedBy()).isNull();
    }

    @Test
    void anEventOfAnotherTypeOrWithoutItsFactsIsUnreadable() throws Exception {
        String good = fixture();
        assertThatThrownBy(() -> DocumentApproved.parse(good.replace("\"eventType\": \"platform.document.approved.v1\"",
            "\"eventType\": \"platform.storage.inbox-arrived.v1\""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentApproved.parse(good.replace("\"extractionId\"", "\"somethingElse\"")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentApproved.parse(good.replace("\"documentTypeId\": 1007", "\"documentTypeId\": 0")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentApproved.parse(good.replace("\"typeVersion\": 2", "\"typeVersion\": \"2\"")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentApproved.parse(good.replace("\"tenantId\": 2961", "\"tenantId\": 0")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workspace");
        assertThatThrownBy(() -> DocumentApproved.parse("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentApproved.parse("{}")).isInstanceOf(IllegalArgumentException.class);
    }

    /** A payload that names a workspace names the envelope's, or the event is not believed. */
    @Test
    void aPayloadNamingAnotherWorkspaceThanItsEnvelopeIsUnreadable() throws Exception {
        String twoWorkspaces = fixture().replace("\"extractionId\": 1180,", "\"extractionId\": 1180, \"tenantId\": 2962,");
        assertThatThrownBy(() -> DocumentApproved.parse(twoWorkspaces)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("two workspaces");
    }
}
