package process.customer;

import org.barco.platform.api.Problem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.pipeline.FileAccessLog;
import process.pipeline.RunReportFiles;
import process.pipeline.SensitiveFiles;
import process.pipeline.backing.ReportStudio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * MIG-426 (owner 2026-10-26): a completed run's result as a document -- pdf, html or md -- wherever a result is handed over
 * (GET /v1/runs/{id}/result, run-and-wait, a webhook's formats). One rule for all of them:
 *
 * <ul>
 *   <li><b>With a report</b> (the run made one; with several, {@code report=RPT-…} names it): its PDF -- the signed one once
 *   approved and signed -- its HTML, or Markdown made from that HTML, its first line "From signed report RPT-… version N"
 *   (Markdown itself is never signed: the PDF is the record). A pipeline marked sensitive, or a report kept as sensitive,
 *   is refused (403 sensitive-files) as its files are to API clients; a report made without the format's source says so
 *   (404 format-not-kept), and one whose files retention removed is 410 (report-expired).</li>
 *   <li><b>Without one</b>: html and md are the result rows as a table -- exactly the rows json gives, masked as the
 *   workspace's data policy masks them -- and pdf is 404 (no-report): a PDF is a report's.</li>
 *   <li>Several reports and none named: 409 (several-reports), naming them.</li>
 * </ul>
 * Every report document served or refused is in the file access log as the client's (or the webhook's) API file read.
 * Converting is not metered: the call (api.calls) or the delivery (webhook.deliveries) already is.
 */
@Service
public class RunDocuments {

    private static final Logger logger = LoggerFactory.getLogger(RunDocuments.class);

    /** The largest document an answer carries. */
    static final long MAX_DOCUMENT_BYTES = 50L * 1024 * 1024;
    /** How many of a run's reports a several-reports problem names. */
    static final int NAMED_REPORTS = 20;
    static final Pattern REPORT_LABEL = Pattern.compile("^RPT-\\d{1,9}$", Pattern.CASE_INSENSITIVE);
    static final String REPORT_REFUSAL = "report is a report's number, as RPT-0093.";

    private final RunReportFiles reports;
    private SensitiveFiles sensitiveFiles;
    private FileAccessLog accessLog;

    public RunDocuments(RunReportFiles reports) {
        this.reports = reports;
    }

    @Autowired(required = false)
    public void useChecks(SensitiveFiles sensitiveFiles, FileAccessLog accessLog) {
        this.sensitiveFiles = sensitiveFiles;
        this.accessLog = accessLog;
    }

    /** A document, or why there is none: a problem, and for several reports their numbers. */
    public static final class Outcome {
        public final ResultDocument document;
        public final Problem problem;
        public final List<String> reports;

        Outcome(ResultDocument document, Problem problem, List<String> reports) {
            this.document = document;
            this.problem = problem;
            this.reports = reports;
        }

        static Outcome of(ResultDocument document) {
            return new Outcome(document, null, null);
        }

        static Outcome refused(Problem problem) {
            return new Outcome(null, problem, null);
        }

        /** The problem as an answer's body about {@code instance}, with the reports it names. */
        public Map<String, Object> problemBody(String instance) {
            Map<String, Object> body = new LinkedHashMap<>(this.problem.at(instance).toMap());
            if (this.reports != null) {
                body.put("reports", this.reports);
            }
            return body;
        }
    }

    /**
     * A completed run's result as {@code format} (pdf, html or md). {@code wanted} names one of its reports (RPT-0093) or is
     * null; {@code rows} gives the result as json does (masked), read only when the rows are the document. {@code who} and
     * {@code via} are for the file access log and media's audit (a client id; api, webhook).
     */
    public Outcome document(long tenantId, long runId, ResultFormat format, String wanted, Supplier<Map<String, Object>> rows, String who,
        String via) {
        if (!format.document()) {
            throw new IllegalArgumentException("json is the result itself, not a document.");
        }
        if (wanted != null && !REPORT_LABEL.matcher(wanted.trim()).matches()) {
            return Outcome.refused(Problem.of(400, REPORT_REFUSAL));
        }
        List<RunReportFiles.Report> made;
        try {
            made = this.reports.unavailable().isPresent() ? new ArrayList<>() : this.reports.of(tenantId, runId);
        } catch (IOException unreachable) {
            logger.warn("Run {}'s reports could not be read for its {} result: {}", runId, format.word, unreachable.getMessage());
            return Outcome.refused(Problem.of(503, "The run's reports cannot be read right now. Try again in a moment."));
        }
        RunReportFiles.Report report = null;
        if (wanted != null) {
            for (RunReportFiles.Report r : made) {
                if (r.label.equalsIgnoreCase(wanted.trim())) {
                    report = r;
                }
            }
            if (report == null) {
                return Outcome.refused(Problem.of(404, "The run made no report " + wanted.trim().toUpperCase(Locale.ROOT)
                    + (made.isEmpty() ? "." : " (it made " + labels(made) + ").")).kind("no-such-report"));
            }
        } else if (made.size() > 1) {
            List<String> named = new ArrayList<>();
            for (int i = 0; i < made.size() && i < NAMED_REPORTS; i++) {
                named.add(made.get(i).label);
            }
            return new Outcome(null, Problem.of(409, "The run made " + made.size() + " reports (" + labels(made) + "); name one with report="
                + made.get(0).label + ", or read each item's files (GET /v1/runs/" + runId + "/items).").kind("several-reports"), named);
        } else if (!made.isEmpty()) {
            report = made.get(0);
        }
        if (report == null) {
            if (format == ResultFormat.PDF) {
                return Outcome.refused(Problem.of(404, "The run made no report, so it has no PDF: ask for json, html or md (its result rows), "
                    + "or add a Generate report step to the pipeline.").kind("no-report"));
            }
            Map<String, Object> result = rows.get();
            if (result == null) {
                return Outcome.refused(Problem.of(503, "The run's result cannot be read right now. Try again in a moment."));
            }
            byte[] bytes = format == ResultFormat.MD ? RowDocuments.markdown(String.valueOf(runId), result)
                : RowDocuments.html(String.valueOf(runId), result);
            return Outcome.of(new ResultDocument(bytes, format.contentType, "run-" + runId + "-result." + format.word, null, null, null));
        }
        return this.ofReport(tenantId, runId, format, report, made.size() > 1, who, via);
    }

    private Outcome ofReport(long tenantId, long runId, ResultFormat format, RunReportFiles.Report report, boolean named, String who,
        String via) {
        String fileName = report.label + "_v" + report.version + "." + format.word;
        if (report.sensitive || this.sensitiveFiles != null && this.sensitiveFiles.sensitiveRun(runId)) {
            this.log(tenantId, who, runId, fileName, false, 403, "sensitive files");
            return Outcome.refused(Problem.of(403, SensitiveFiles.API_REFUSAL).kind("sensitive-files"));
        }
        if (!report.has(format.word)) {
            return this.notKept(tenantId, who, runId, fileName, format, report);
        }
        // The Markdown's pictures and signatures link to where they are seen: the report's signed PDF (else its HTML).
        String linkTo = "/v1/runs/" + runId + "/result?format=" + (report.has("pdf") ? "pdf" : "html") + (named ? "&report=" + report.label : "");
        ReportStudio.Delivered delivered;
        try {
            delivered = this.reports.deliver(tenantId, report, format.word, format == ResultFormat.MD ? linkTo : null, via, MAX_DOCUMENT_BYTES);
        } catch (ReportStudio.Refused refused) {
            if (refused.status == 404) {
                return this.notKept(tenantId, who, runId, fileName, format, report);
            }
            if (refused.status == 410) {
                this.log(tenantId, who, runId, fileName, false, 410, "report expired");
                return Outcome.refused(Problem.of(410, "Report " + report.label + " version " + report.version + "'s files were removed by the "
                    + "workspace's retention policy.").kind("report-expired"));
            }
            logger.warn("Run {}'s report {} could not be delivered as {}: {}", runId, report.label, format.word, refused.getMessage());
            return Outcome.refused(Problem.of(503, "The run's report cannot be read right now. Try again in a moment."));
        } catch (IOException failed) {
            logger.warn("Run {}'s report {} could not be delivered as {}: {}", runId, report.label, format.word, failed.getMessage());
            return Outcome.refused(Problem.of(503, "The run's report cannot be read right now. Try again in a moment."));
        }
        this.log(tenantId, who, runId, fileName, true, 200, null);
        return Outcome.of(new ResultDocument(delivered.bytes, format.contentType, fileName, report.label, report.version, report.signed));
    }

    private Outcome notKept(long tenantId, String who, long runId, String fileName, ResultFormat format, RunReportFiles.Report report) {
        this.log(tenantId, who, runId, fileName, false, 404, "format not kept");
        String made = report.formats.isEmpty() ? "" : " was made as " + String.join(" and ", report.formats) + " only, so it";
        String has = format == ResultFormat.PDF ? "has no PDF" : format == ResultFormat.HTML ? "has no HTML"
            : "has no Markdown (Markdown is made from its HTML)";
        return Outcome.refused(Problem.of(404, "Report " + report.label + " version " + report.version + made + " " + has + ": ask for "
            + (format == ResultFormat.PDF ? "html, md" : "pdf") + " or json, or add " + (format == ResultFormat.PDF ? "PDF" : "HTML")
            + " to the Generate report step's formats.").kind("format-not-kept"));
    }

    private void log(long tenantId, String who, long runId, String fileName, boolean served, int status, String reason) {
        if (this.accessLog != null) {
            this.accessLog.apiFile(tenantId, who, null, runId, fileName, served, status, reason);
        }
    }

    private static String labels(List<RunReportFiles.Report> made) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < made.size() && i < NAMED_REPORTS; i++) {
            out.append(i == 0 ? "" : ", ").append(made.get(i).label);
        }
        return made.size() > NAMED_REPORTS ? out.append(", and ").append(made.size() - NAMED_REPORTS).append(" more").toString() : out.toString();
    }
}
