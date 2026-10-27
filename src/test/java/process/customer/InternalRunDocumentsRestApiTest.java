package process.customer;

import org.barco.platform.api.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-426: a webhook's chosen formats of a run's result, for integration-service: the internal token, the event's workspace,
 * json as the API gives it, md and html as text, pdf as base64, and a format the run cannot be given in -- or one past what a
 * delivery carries -- as its problem.
 */
class InternalRunDocumentsRestApiTest {

    private final CustomerRuns runs = mock(CustomerRuns.class);
    private final InternalRunDocumentsRestApi api = new InternalRunDocumentsRestApi(this.runs, "internal-secret");

    private static InternalRunDocumentsRestApi.DocumentsRequest request(Long maxBytes, String... formats) {
        InternalRunDocumentsRestApi.DocumentsRequest r = new InternalRunDocumentsRestApi.DocumentsRequest();
        r.setTenantId(1006L);
        r.setRunId(2985L);
        r.setFormats(Arrays.asList(formats));
        r.setVia("webhook");
        r.setWho("webhook:41");
        r.setMaxBytes(maxBytes);
        return r;
    }

    private void runHas(Map<ResultFormat, Object> answers) {
        when(this.runs.resultsFor(eq(1006L), eq(2985L), any(), any(), eq("webhook:41"), eq("webhook"))).thenReturn(Optional.of(answers));
    }

    @Test
    void withoutTheInternalTokenNothingIsRead() {
        assertThat(this.api.runDocuments(null, request(null, "json")).getStatusCode().value()).isEqualTo(401);
        assertThat(this.api.runDocuments("guess", request(null, "json")).getStatusCode().value()).isEqualTo(401);
        verify(this.runs, never()).resultsFor(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void anUnsupportedFormatIs400() {
        ResponseEntity<?> answer = this.api.runDocuments("internal-secret", request(null, "json", "docx"));
        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(answer.getBody().toString()).contains("format is one of json, pdf, html, md.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachChosenFormatIsGivenOrSaysWhyNot() {
        Map<ResultFormat, Object> answers = new LinkedHashMap<>();
        answers.put(ResultFormat.JSON, Collections.singletonMap("rowCount", 2));
        answers.put(ResultFormat.MD, RunDocuments.Outcome.of(new ResultDocument("From signed report RPT-0093 version 1\n".getBytes(StandardCharsets.UTF_8),
            ResultFormat.MD.contentType, "RPT-0093_v1.md", "RPT-0093", 1, true)));
        answers.put(ResultFormat.PDF, RunDocuments.Outcome.of(new ResultDocument("%PDF-1.7 signed".getBytes(StandardCharsets.UTF_8),
            ResultFormat.PDF.contentType, "RPT-0093_v1.pdf", "RPT-0093", 1, true)));
        answers.put(ResultFormat.HTML, RunDocuments.Outcome.refused(Problem.of(403, "sensitive").kind("sensitive-files")));
        this.runHas(answers);

        ResponseEntity<?> answer = this.api.runDocuments("internal-secret", request(null, "json", "md", "pdf", "html", "md"));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) answer.getBody();
        Map<String, Object> results = (Map<String, Object>) body.get("results");
        assertThat(results.get("json")).isEqualTo(Collections.singletonMap("rowCount", 2));
        assertThat((Map<String, Object>) results.get("md")).containsEntry("text", "From signed report RPT-0093 version 1\n")
            .containsEntry("report", "RPT-0093").containsEntry("version", 1).containsEntry("signed", true).containsEntry("fileName", "RPT-0093_v1.md");
        assertThat((Map<String, Object>) results.get("pdf")).containsEntry("base64", Base64.getEncoder().encodeToString("%PDF-1.7 signed".getBytes(
            StandardCharsets.UTF_8))).containsEntry("bytes", 15).doesNotContainKey("text");
        Map<String, Object> problems = (Map<String, Object>) body.get("problems");
        assertThat((Map<String, Object>) problems.get("html")).containsEntry("status", 403).containsEntry("type", "/problems/sensitive-files");
        verify(this.runs).resultsFor(eq(1006L), eq(2985L), eq(Arrays.asList(ResultFormat.JSON, ResultFormat.MD, ResultFormat.PDF, ResultFormat.HTML)),
            any(), eq("webhook:41"), eq("webhook"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFormatPastWhatADeliveryCarriesIsTooLarge() {
        Map<ResultFormat, Object> answers = new LinkedHashMap<>();
        answers.put(ResultFormat.MD, RunDocuments.Outcome.of(new ResultDocument(new byte[40], ResultFormat.MD.contentType, "a.md", "RPT-0001", 1, false)));
        answers.put(ResultFormat.PDF, RunDocuments.Outcome.of(new ResultDocument(new byte[60], ResultFormat.PDF.contentType, "a.pdf", "RPT-0001", 1, false)));
        this.runHas(answers);

        Map<String, Object> body = (Map<String, Object>) this.api.runDocuments("internal-secret", request(100L, "md", "pdf")).getBody();

        assertThat((Map<String, Object>) body.get("results")).containsOnlyKeys("md");
        Map<String, Object> tooLarge = (Map<String, Object>) ((Map<String, Object>) body.get("problems")).get("pdf");
        assertThat(tooLarge).containsEntry("status", 413).containsEntry("type", "/problems/too-large");
        assertThat(String.valueOf(tooLarge.get("detail"))).contains("GET /v1/runs/2985/result?format=pdf");
    }

    @Test
    void anotherWorkspacesRunIsNotFound() {
        when(this.runs.resultsFor(anyLong(), anyLong(), any(), any(), any(), any())).thenReturn(Optional.empty());
        assertThat(this.api.runDocuments("internal-secret", request(null, "json")).getStatusCode().value()).isEqualTo(404);
    }
}
