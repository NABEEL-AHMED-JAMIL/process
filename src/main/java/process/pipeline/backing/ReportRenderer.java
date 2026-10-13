package process.pipeline.backing;

import java.io.OutputStream;
import java.util.Optional;

/**
 * A run's report as a PDF (MIG-255): media-service renders it -- Core never draws a page -- for the run's workspace,
 * billed once per step and attempt under the usage key.
 *
 * ADR-027 (big-data PDFs): the report goes as {@link ReportLines} -- written to the step's scratch directory as its rows
 * are read, never held -- and the PDF comes back as a stream the step writes straight to the run's file area. Neither
 * side holds the table, and nothing is base64.
 */
public interface ReportRenderer {

    /** Why reports cannot be rendered here, or empty when they can. */
    Optional<String> unavailable();

    /** Renders the report in {@code lines} as the run's workspace, writing the PDF to {@code pdf}; returns what was drawn. */
    Rendered render(long tenantId, String usageKey, ReportLines lines, OutputStream pdf) throws Exception;

    /** What media-service drew: the PDF's pages and the table rows printed. */
    final class Rendered {
        public final int pages;
        public final long rows;

        public Rendered(int pages, long rows) {
            this.pages = pages;
            this.rows = rows;
        }
    }
}
