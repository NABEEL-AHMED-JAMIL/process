package process.pipeline.tasks;

import process.pipeline.RunDocuments;
import process.pipeline.StepContext;
import process.pipeline.data.Values;
import process.util.BusinessTime;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {{placeholders}} in a step's text settings (MIG-231): a bucket key, a notice's title, an API variable. The run's are
 * {{run}}, {{attempt}}, {{step}}, {{job}}, {{pipeline}}, {{date}} (yyyy-MM-dd) and {{time}} (HHmmss) on the business
 * clock (America/Chicago), and {{rows}} where a step has rows; for a run started for a file (an inbox arrival, a form
 * submission, an API run) {{input_key}} (its key in the workspace's storage) and {{input_name}} (the key's last part),
 * and (MIG-360) {{input_count}} and {{input_keys}} -- how many files, and their keys joined by commas, for a run that took
 * a batch of inbox files; a row's are its columns, {{column}}. MIG-364: for a run an approved document started,
 * {{document_count}} and {{document_ids}} (the documents' ids joined by commas), and the first document's values as
 * {{document.<field>}} (and {{document.id}}, {{document.file_name}}, {{document.approval}}, ...) -- read from Document
 * Intelligence only when a template names one; in a run no document started, {{document.<field>}} and {{document_ids}} are
 * empty and {{document_count}} is 0. MIG-347: the run's parameters as {{param.<name>}}, and {{run.date}}, {{run.windowStart}}
 * and {{run.windowEnd}} -- the day the run is for in the workspace's billing zone and its period. An unknown
 * placeholder is left as written, so a mistake shows in the result instead of vanishing.
 */
final class Templates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\- ]+?)\\s*}}");

    private Templates() {
    }

    static Map<String, Object> ofRun(StepContext context, Integer rows) {
        LocalDateTime now = BusinessTime.now();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("run", context.jobQueueId());
        values.put("attempt", context.attempt());
        values.put("step", context.stepKey());
        values.put("job", context.jobId());
        values.put("pipeline", context.pipelineId());
        values.put("date", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        values.put("time", now.format(DateTimeFormatter.ofPattern("HHmmss")));
        if (rows != null) {
            values.put("rows", rows);
        }
        // MIG-347: {{param.name}} and {{run.date}}, {{run.windowStart}}, {{run.windowEnd}} -- in the step's settings already,
        // and in what a step fills from its own templates (a row's text, a notice's body).
        values.putAll(context.runValues());
        String inputKey = context.inputKey();
        if (inputKey != null && !inputKey.isEmpty()) {
            values.put("input_key", inputKey);
            values.put("input_name", inputKey.substring(inputKey.lastIndexOf('/') + 1));
            List<String> keys = context.inputKeys();
            values.put("input_count", keys.size());
            values.put("input_keys", String.join(",", keys));
        }
        List<RunDocuments.Document> documents = context.documents();
        if (documents.isEmpty()) {
            // A pipeline that runs both ways (a document's approval, Run now) says nothing where a document would be.
            values.put(DOCUMENT_VALUES, (Supplier<Map<String, Object>>) Collections::emptyMap);
            values.put(NO_DOCUMENT, Boolean.TRUE);
            // MIG-364 leftover (UX review 2026-10-12): a run started by hand read "{{document_count}}" in its notice. No
            // document is 0 documents and no ids, as {{document.<field>}} is empty.
            values.put("document_count", 0);
            values.put("document_ids", "");
        } else {
            values.put("document_count", documents.size());
            StringBuilder ids = new StringBuilder();
            for (RunDocuments.Document d : documents) {
                ids.append(ids.length() == 0 ? "" : ",").append(d.extractionId);
            }
            values.put("document_ids", ids.toString());
            values.put("document.id", documents.get(0).extractionId);
            values.put(DOCUMENT_VALUES, (Supplier<Map<String, Object>>) () -> {
                try {
                    return context.documentValues();
                } catch (Exception unread) {
                    throw new IllegalStateException("The approved document's values could not be read: " + unread.getMessage(), unread);
                }
            });
        }
        return values;
    }

    /** MIG-364: where {@link #ofRun} leaves the run's document values to read, only if a template names one. */
    static final String DOCUMENT_VALUES = "\u0000document";
    /** MIG-364: the run was not started by a document: {{document.<field>}} is empty rather than left as written. */
    static final String NO_DOCUMENT = "\u0000no-document";

    static String fill(String template, Map<String, Object> values) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name.startsWith("document.") && !values.containsKey(name) && values.get(DOCUMENT_VALUES) instanceof Supplier) {
                documentValues(values);
            }
            if (name.startsWith("document.") && !values.containsKey(name) && Boolean.TRUE.equals(values.get(NO_DOCUMENT))) {
                matcher.appendReplacement(out, "");
                continue;
            }
            String replacement = values.containsKey(name) ? String.valueOf(Values.text(values.get(name)) == null ? ""
                : Values.text(values.get(name))) : matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The run's document values as document.<key>, read once (the supplier is then dropped). */
    @SuppressWarnings("unchecked")
    private static void documentValues(Map<String, Object> values) {
        Object supplier = values.remove(DOCUMENT_VALUES);
        if (!(supplier instanceof Supplier)) {
            return;
        }
        Map<String, Object> document = ((Supplier<Map<String, Object>>) supplier).get();
        for (Map.Entry<String, Object> value : document.entrySet()) {
            values.putIfAbsent("document." + value.getKey(), value.getValue());
        }
    }
}
