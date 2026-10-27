package process.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import process.pipeline.FileAccessLog;
import process.pipeline.RunReportFiles;
import process.pipeline.SensitiveFiles;
import process.pipeline.backing.ReportStudio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-426: a completed run's result as pdf, html or md -- its report as media delivers it (the signed PDF, Markdown from its
 * HTML), or with no report its rows; a sensitive pipeline's report refused as its files are; one report of several named.
 */
class RunDocumentsTest {

    static final long TENANT = 1006L;
    static final long RUN = 2985L;
    static final ObjectMapper JSON = new ObjectMapper();

    /** media as the tests need it: the run's reports, and each delivery recorded. */
    static final class Studio implements ReportStudio {
        final ArrayNode reports = JSON.createArrayNode();
        final List<String> delivered = new ArrayList<>();
        Refused refuse;
        IOException down;

        ObjectNode report(String id, int label, int version, String step, int attempt, String row, String... formats) {
            ObjectNode r = this.reports.addObject().put("reportId", id).put("reportLabel", String.format("RPT-%04d", label)).put("version", version)
                .put("stepKey", step).put("attempt", attempt).put("approved", false).put("signed", false).put("sensitive", false);
            if (row != null) {
                r.put("row", row);
            }
            ArrayNode f = r.putArray("formats");
            for (String one : formats) {
                f.add(one);
            }
            return r;
        }

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }

        @Override
        public JsonNode generate(long tenantId, Long userId, String userName, String idempotencyKey, Path lines) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode job(long tenantId, String jobId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void file(long tenantId, String reportId, int version, String format, OutputStream out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode check(long tenantId, Collection<String> ids, String version, Collection<String> columns) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode runReports(long tenantId, long jobQueueId) throws IOException {
            if (this.down != null) {
                throw this.down;
            }
            return tenantId == TENANT && jobQueueId == RUN ? this.reports : JSON.createArrayNode();
        }

        @Override
        public Delivered deliver(long tenantId, String reportId, int version, String format, String linkTo, String via, long maxBytes)
            throws IOException {
            this.delivered.add(tenantId + " " + reportId + " v" + version + " " + format + " " + linkTo + " " + via);
            if (this.refuse != null) {
                throw this.refuse;
            }
            String body = "PDF".equals(format) ? "%PDF-signed " + reportId : "MD".equals(format) ? "From signed report " + reportId + "\n" : "<html>" + reportId;
            return new Delivered(body.getBytes(StandardCharsets.UTF_8), "PDF".equals(format) ? "application/pdf" : "text/plain", null);
        }
    }

    private final Studio studio = new Studio();
    private final SensitiveFiles sensitive = mock(SensitiveFiles.class);
    private final FileAccessLog log = mock(FileAccessLog.class);
    private final RunDocuments documents = documents();
    private final AtomicInteger rowsRead = new AtomicInteger();

    private RunDocuments documents() {
        RunDocuments d = new RunDocuments(new RunReportFiles(this.studio));
        d.useChecks(this.sensitive, this.log);
        return d;
    }

    private Map<String, Object> rows() {
        this.rowsRead.incrementAndGet();
        return RowDocumentsTest.result(RowDocumentsTest.rows(), 2, Collections.singletonList("patient"));
    }

    private RunDocuments.Outcome ask(ResultFormat format, String report) {
        return this.documents.document(TENANT, RUN, format, report, this::rows, "cl_riverside", "api");
    }

    private static String text(RunDocuments.Outcome o) {
        return new String(o.document.bytes, StandardCharsets.UTF_8);
    }

    @Test
    void aRunsOneReportIsItsPdfHtmlAndMarkdown() {
        this.studio.report("01REPORT", 93, 1, "report", 1, null, "PDF", "HTML").put("signed", true).put("approved", true);

        RunDocuments.Outcome pdf = this.ask(ResultFormat.PDF, null);
        RunDocuments.Outcome md = this.ask(ResultFormat.MD, null);
        RunDocuments.Outcome html = this.ask(ResultFormat.HTML, null);

        assertThat(text(pdf)).isEqualTo("%PDF-signed 01REPORT");
        assertThat(pdf.document.contentType).isEqualTo("application/pdf");
        assertThat(pdf.document.fileName).isEqualTo("RPT-0093_v1.pdf");
        assertThat(pdf.document.headers).containsEntry("X-Result-Source", "report").containsEntry("X-Report", "RPT-0093")
            .containsEntry("X-Report-Version", "1").containsEntry("X-Report-Signed", "true");
        assertThat(text(md)).startsWith("From signed report 01REPORT");
        assertThat(md.document.contentType).isEqualTo("text/markdown; charset=utf-8");
        assertThat(html.document.contentType).isEqualTo("text/html; charset=utf-8");
        assertThat(this.studio.delivered).containsExactly("1006 01REPORT v1 PDF null api",
            "1006 01REPORT v1 MD /v1/runs/2985/result?format=pdf api", "1006 01REPORT v1 HTML null api");
        assertThat(this.rowsRead).as("a report's document never reads the rows").hasValue(0);
        verify(this.log).apiFile(TENANT, "cl_riverside", null, RUN, "RPT-0093_v1.md", true, 200, null);
    }

    @Test
    void aRunWithoutAReportGivesItsRowsAsHtmlOrMarkdownAndNoPdf() {
        RunDocuments.Outcome md = this.ask(ResultFormat.MD, null);
        RunDocuments.Outcome html = this.ask(ResultFormat.HTML, null);
        RunDocuments.Outcome pdf = this.ask(ResultFormat.PDF, null);

        assertThat(text(md)).startsWith("From the result of run 2985 (no report).\n").contains("| patient | flag | note | meds |");
        assertThat(md.document.fileName).isEqualTo("run-2985-result.md");
        assertThat(md.document.headers).containsEntry("X-Result-Source", "rows").doesNotContainKey("X-Report");
        assertThat(text(html)).contains("<th>patient</th>");
        assertThat(pdf.document).isNull();
        assertThat(pdf.problem.getStatus()).isEqualTo(404);
        assertThat(pdf.problem.getType()).isEqualTo("/problems/no-report");
        assertThat(pdf.problem.getDetail()).contains("no report, so it has no PDF").contains("json, html or md");
        assertThat(this.studio.delivered).isEmpty();
    }

    @Test
    void aSensitivePipelinesReportIsRefusedInEveryFormatAndLogged() {
        this.studio.report("01REPORT", 93, 1, "report", 1, null, "PDF", "HTML");
        when(this.sensitive.sensitiveRun(RUN)).thenReturn(true);

        for (ResultFormat format : Arrays.asList(ResultFormat.PDF, ResultFormat.HTML, ResultFormat.MD)) {
            RunDocuments.Outcome o = this.ask(format, null);
            assertThat(o.document).as(format.word).isNull();
            assertThat(o.problem.getStatus()).isEqualTo(403);
            assertThat(o.problem.getType()).isEqualTo("/problems/sensitive-files");
            assertThat(o.problem.getDetail()).isEqualTo(SensitiveFiles.API_REFUSAL);
        }
        assertThat(this.studio.delivered).isEmpty();
        verify(this.log).apiFile(TENANT, "cl_riverside", null, RUN, "RPT-0093_v1.md", false, 403, "sensitive files");
    }

    @Test
    void aReportMediaKeepsAsSensitiveIsRefusedToo() {
        this.studio.report("01REPORT", 93, 1, "report", 1, null, "PDF", "HTML").put("sensitive", true);
        assertThat(this.ask(ResultFormat.PDF, null).problem.getStatus()).isEqualTo(403);
        assertThat(this.studio.delivered).isEmpty();
    }

    @Test
    void severalReportsAreNamedAndOneIsChosenByItsNumber() {
        this.studio.report("01A", 11, 1, "report", 1, "i1", "PDF", "HTML");
        this.studio.report("01B", 12, 1, "report", 1, "i2", "PDF", "HTML");

        RunDocuments.Outcome both = this.ask(ResultFormat.MD, null);
        assertThat(both.problem.getStatus()).isEqualTo(409);
        assertThat(both.problem.getType()).isEqualTo("/problems/several-reports");
        assertThat(both.reports).containsExactly("RPT-0011", "RPT-0012");
        assertThat(both.problemBody("/v1/runs/2985/result")).containsEntry("reports", Arrays.asList("RPT-0011", "RPT-0012"));
        RunDocuments.Outcome one = this.ask(ResultFormat.MD, "rpt-0012");
        assertThat(text(one)).startsWith("From signed report 01B");
        assertThat(this.studio.delivered).containsExactly("1006 01B v1 MD /v1/runs/2985/result?format=pdf&report=RPT-0012 api");
        RunDocuments.Outcome none = this.ask(ResultFormat.MD, "RPT-0099");
        assertThat(none.problem.getStatus()).isEqualTo(404);
        assertThat(none.problem.getDetail()).isEqualTo("The run made no report RPT-0099 (it made RPT-0011, RPT-0012).");
        assertThat(this.ask(ResultFormat.MD, "0012").problem.getStatus()).isEqualTo(400);
    }

    @Test
    void aRetriedRunStandsOnItsNewestAttemptsReport() {
        this.studio.report("01OLD", 20, 1, "report", 1, null, "PDF", "HTML");
        this.studio.report("01NEW", 21, 1, "report", 2, null, "PDF", "HTML");

        assertThat(text(this.ask(ResultFormat.PDF, null))).isEqualTo("%PDF-signed 01NEW");
    }

    @Test
    void aReportMadeWithoutTheFormatsSourceSaysSo() {
        this.studio.report("01REPORT", 93, 1, "report", 1, null, "PDF");

        RunDocuments.Outcome md = this.ask(ResultFormat.MD, null);

        assertThat(md.problem.getStatus()).isEqualTo(404);
        assertThat(md.problem.getType()).isEqualTo("/problems/format-not-kept");
        assertThat(md.problem.getDetail()).isEqualTo("Report RPT-0093 version 1 was made as PDF only, so it has no Markdown (Markdown is made from "
            + "its HTML): ask for pdf or json, or add HTML to the Generate report step's formats.");
        assertThat(this.studio.delivered).isEmpty();
    }

    @Test
    void retentionsRemovedFilesAre410AndMediaDownIs503() {
        this.studio.report("01REPORT", 93, 1, "report", 1, null, "PDF", "HTML");
        this.studio.refuse = new ReportStudio.Refused(410, "REPORT_EXPIRED", "removed");
        RunDocuments.Outcome gone = this.ask(ResultFormat.PDF, null);
        assertThat(gone.problem.getStatus()).isEqualTo(410);
        assertThat(gone.problem.getType()).isEqualTo("/problems/report-expired");

        this.studio.refuse = new ReportStudio.Refused(502, "OUTPUT_STORAGE_FAILED", "storage");
        assertThat(this.ask(ResultFormat.PDF, null).problem.getStatus()).isEqualTo(503);
        this.studio.refuse = null;
        this.studio.down = new IOException("connection refused");
        assertThat(this.ask(ResultFormat.MD, null).problem.getStatus()).isEqualTo(503);
    }
}
