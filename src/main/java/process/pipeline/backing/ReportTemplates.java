package process.pipeline.backing;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * AI hard-workflow test (2026-10-13): which ids are the workspace's report templates (media-service's report_template),
 * so a render_pdf step naming a prompt's id -- or a template that is gone -- as its templateId is refused when the pipeline
 * is saved, not when a run renders. media-service answers GET /internal/media/render/templates with the internal token.
 */
public interface ReportTemplates {

    /** Those of the ids that are live templates of the workspace, id -> name; IOException when media-service cannot say. */
    Map<Long, String> templatesOf(long tenantId, Collection<Long> ids) throws IOException;
}
