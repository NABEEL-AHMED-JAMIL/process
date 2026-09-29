package process.pipeline.backing;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A run's report as a PDF (MIG-255): media-service renders it -- Core never draws a page -- from the rows the step sends,
 * for the run's workspace, billed once per step and attempt under the usage key.
 */
public interface ReportRenderer {

    /** Why reports cannot be rendered here, or empty when they can. */
    Optional<String> unavailable();

    /** @return the PDF's bytes */
    byte[] renderPdf(Report report) throws Exception;

    /** What to render: a title, header fields, the columns in order and each row's values in that order. */
    final class Report {
        public final long tenantId;
        public final String usageKey;
        public final String fileName;
        public final String title;
        public final Map<String, String> fields;
        public final List<String> columns;
        public final List<List<Object>> rows;
        public final Long templateId;

        public Report(long tenantId, String usageKey, String fileName, String title, Map<String, String> fields, List<String> columns,
            List<List<Object>> rows, Long templateId) {
            this.tenantId = tenantId;
            this.usageKey = usageKey;
            this.fileName = fileName;
            this.title = title;
            this.fields = fields;
            this.columns = columns;
            this.rows = rows;
            this.templateId = templateId;
        }
    }
}
