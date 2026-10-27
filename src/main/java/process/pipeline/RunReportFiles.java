package process.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import process.pipeline.backing.ReportStudio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MIG-426: the reports a run made, as Report Studio keeps them, and each as a client app gets it -- for the customer API's
 * result formats, a webhook's formats and a Call API step's report. A run's reports are media's (runReports): for each step
 * and row (item) the newest attempt's, so a retried run stands on what its latest attempt made, and a per-row report a retry
 * reused (MIG-421) is still its item's. Delivered in pdf (the signed PDF once approved and signed), html or md (made from the
 * HTML) by media ({@link ReportStudio#deliver}).
 */
@Component
public class RunReportFiles {

    private final ReportStudio studio;

    public RunReportFiles(ReportStudio studio) {
        this.studio = studio;
    }

    /** One report of a run. */
    public static final class Report {
        public String reportId;
        /** RPT-0093. */
        public String label;
        public int version;
        /** The step that made it; null for a report whose job named none. */
        public String stepKey;
        public int attempt;
        /** The row (item) it was made for, with one report per row; null for one report of all the rows. */
        public String row;
        /** What it was made as: PDF, HTML (empty when media did not say). */
        public Set<String> formats = new LinkedHashSet<>();
        public boolean approved;
        public boolean signed;
        public boolean sensitive;
        public String voidReason;

        /** Whether it was made as this format's source: PDF for pdf, HTML for html and md. */
        public boolean has(String format) {
            String source = "pdf".equalsIgnoreCase(format) ? "PDF" : "HTML";
            return this.formats.isEmpty() || this.formats.contains(source);
        }

        static Report of(JsonNode node) {
            Report r = new Report();
            r.reportId = node.path("reportId").asText("");
            r.label = node.path("reportLabel").asText(r.reportId);
            r.version = node.path("version").asInt();
            r.stepKey = node.hasNonNull("stepKey") ? node.path("stepKey").asText() : null;
            r.attempt = node.path("attempt").asInt(0);
            r.row = node.hasNonNull("row") ? node.path("row").asText() : null;
            for (JsonNode f : node.path("formats")) {
                r.formats.add(f.asText().toUpperCase(Locale.ROOT));
            }
            r.approved = node.path("approved").asBoolean(false);
            r.signed = node.path("signed").asBoolean(false);
            r.sensitive = node.path("sensitive").asBoolean(false);
            r.voidReason = node.hasNonNull("voidReason") ? node.path("voidReason").asText() : null;
            return r;
        }
    }

    /** Whether reports can be read here at all (media's pipeline reports are on); empty when they can. */
    public Optional<String> unavailable() {
        return this.studio.unavailable();
    }

    /** The run's reports, in the order they were made: for each step and row, the newest attempt's. */
    public List<Report> of(long tenantId, long jobQueueId) throws IOException {
        Map<String, Report> newest = new LinkedHashMap<>();
        for (JsonNode node : this.studio.runReports(tenantId, jobQueueId)) {
            Report r = Report.of(node);
            if (r.reportId.isEmpty()) {
                continue;
            }
            // Media's older reports name no step: each is its own.
            String key = r.stepKey == null ? "report\u0000" + r.reportId : r.stepKey + "\u0000" + (r.row == null ? "" : r.row);
            Report before = newest.get(key);
            if (before == null || r.attempt >= before.attempt) {
                newest.remove(key);
                newest.put(key, r);
            }
        }
        return new ArrayList<>(newest.values());
    }

    /** The reports a step of the run made (its newest attempt's), in order. */
    public List<Report> ofStep(long tenantId, long jobQueueId, String stepKey) throws IOException {
        List<Report> out = new ArrayList<>();
        for (Report r : this.of(tenantId, jobQueueId)) {
            if (stepKey.equals(r.stepKey)) {
                out.add(r);
            }
        }
        return out;
    }

    /** A report as a client app gets it: pdf, html or md, at most {@code maxBytes}; media's refusal is a {@link ReportStudio.Refused}. */
    public ReportStudio.Delivered deliver(long tenantId, Report report, String format, String linkTo, String via, long maxBytes)
        throws IOException {
        return this.studio.deliver(tenantId, report.reportId, report.version, format.toUpperCase(Locale.ROOT), linkTo, via, maxBytes);
    }
}
