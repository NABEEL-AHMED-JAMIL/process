package process.pipeline.backing;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;

/**
 * A run's report as a PDF (MIG-255): media-service renders it -- Core never draws a page -- from the rows the step sends,
 * for the run's workspace, billed once per step and attempt under the usage key.
 */
public interface ReportRenderer {

    /** Why reports cannot be rendered here, or empty when they can. */
    Optional<String> unavailable();

    /** @return the PDF's bytes */
    byte[] renderPdf(Report report) throws Exception;

    /** What to render: a title, header fields, the columns in order, each row's values in that order, and how the page looks. */
    final class Report {
        public final long tenantId;
        public final String usageKey;
        public final String fileName;
        public final String title;
        public final Map<String, String> fields;
        public final List<String> columns;
        public final List<List<Object>> rows;
        public final Long templateId;
        /** portrait or landscape, for Core's own template (a named template keeps its own). */
        public final String orientation;
        /** Printed across every page (DRAFT), or null. */
        public final String watermark;
        /** MIG-346: the report's tables when it has several, in order (the main columns and rows are then empty); empty for one. */
        public final List<Section> sections;

        public Report(long tenantId, String usageKey, String fileName, String title, Map<String, String> fields, List<String> columns,
            List<List<Object>> rows, Long templateId, String orientation, String watermark) {
            this(tenantId, usageKey, fileName, title, fields, columns, rows, templateId, orientation, watermark,
                Collections.<Section>emptyList());
        }

        public Report(long tenantId, String usageKey, String fileName, String title, Map<String, String> fields, List<String> columns,
            List<List<Object>> rows, Long templateId, String orientation, String watermark, List<Section> sections) {
            this.tenantId = tenantId;
            this.usageKey = usageKey;
            this.fileName = fileName;
            this.title = title;
            this.fields = fields;
            this.columns = columns;
            this.rows = rows;
            this.templateId = templateId;
            this.orientation = orientation;
            this.watermark = watermark;
            this.sections = sections == null ? Collections.<Section>emptyList() : sections;
        }
    }

    /** MIG-346: one table of a report with several: its title, a note under it (or null), its columns and rows. */
    final class Section {
        public final String title;
        public final String note;
        public final List<String> columns;
        public final List<List<Object>> rows;

        public Section(String title, String note, List<String> columns, List<List<Object>> rows) {
            this.title = title;
            this.note = note;
            this.columns = columns;
            this.rows = rows;
        }
    }
}
