package process.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import process.pipeline.data.Values;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MIG-426: a run with no report, as a document -- its result (the final output's rows, already masked as the workspace's
 * data policy masks them, at most 100 rows and 16 KB: {@link CustomerRuns#resultOf}) as a Markdown table, or as an HTML page
 * with one table. Never signed, never a report: the first line says it is the run's result. A value that is a list or an
 * object is written as its JSON; a | or a line break in a value cannot break the table, and in HTML every value is escaped.
 */
final class RowDocuments {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RowDocuments() {
    }

    /** "From the result of run 2985 (no report)." */
    static String firstLine(String runId) {
        return "From the result of run " + runId + " (no report).";
    }

    /** What the rows are: the step, how many of how many, what is masked, and that it is never signed. */
    static String note(Map<String, Object> result) {
        Object step = result.get("step");
        long total = number(result.get("rowCount"));
        int shown = rows(result).size();
        StringBuilder note = new StringBuilder();
        note.append(step == null ? "The run's final output" : "The output of step " + step);
        note.append(": ").append(shown == total ? String.format("%,d row%s", total, total == 1 ? "" : "s")
            : String.format("the first %,d of %,d rows", shown, total));
        Collection<?> masked = result.get("masked") instanceof Collection ? (Collection<?>) result.get("masked") : null;
        if (masked != null && !masked.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Object m : masked) {
                names.append(names.length() == 0 ? "" : ", ").append(m);
            }
            note.append("; masked by the workspace's data policy: ").append(names);
        }
        return note.append(". Not signed: ask the pipeline for a report for a signed PDF.").toString();
    }

    static byte[] markdown(String runId, Map<String, Object> result) {
        StringBuilder out = new StringBuilder();
        out.append(firstLine(runId)).append("\n\n> ").append(mdText(note(result))).append("\n\n");
        Object problem = result.get("problem");
        List<String> columns = columns(result);
        List<Map<String, Object>> rows = rows(result);
        if (problem != null) {
            out.append('_').append(mdText(String.valueOf(problem))).append("_\n");
        } else if (columns.isEmpty() || rows.isEmpty()) {
            out.append("_No rows._\n");
        } else {
            out.append('|');
            for (String c : columns) {
                out.append(' ').append(mdCell(c)).append(" |");
            }
            out.append("\n|");
            for (int i = 0; i < columns.size(); i++) {
                out.append(" --- |");
            }
            for (Map<String, Object> row : rows) {
                out.append("\n|");
                for (String c : columns) {
                    out.append(' ').append(mdCell(cell(row.get(c)))).append(" |");
                }
            }
            out.append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] html(String runId, Map<String, Object> result) {
        StringBuilder out = new StringBuilder();
        String title = "Result of run " + runId;
        out.append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">")
            .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">")
            .append("<title>").append(escape(title)).append("</title><style>")
            .append("body{font-family:system-ui,sans-serif;margin:24px;color:#1f2937}table{border-collapse:collapse}")
            .append("th,td{border:1px solid #d1d5db;padding:4px 8px;text-align:left;vertical-align:top}th{background:#f3f4f6}")
            .append(".note{color:#4b5563}</style></head><body>\n");
        out.append("<p>").append(escape(firstLine(runId))).append("</p>\n<p class=\"note\">").append(escape(note(result))).append("</p>\n");
        Object problem = result.get("problem");
        List<String> columns = columns(result);
        List<Map<String, Object>> rows = rows(result);
        if (problem != null) {
            out.append("<p><em>").append(escape(String.valueOf(problem))).append("</em></p>\n");
        } else if (columns.isEmpty() || rows.isEmpty()) {
            out.append("<p><em>No rows.</em></p>\n");
        } else {
            out.append("<table><thead><tr>");
            for (String c : columns) {
                out.append("<th>").append(escape(c)).append("</th>");
            }
            out.append("</tr></thead><tbody>\n");
            for (Map<String, Object> row : rows) {
                out.append("<tr>");
                for (String c : columns) {
                    out.append("<td>").append(escape(cell(row.get(c)))).append("</td>");
                }
                out.append("</tr>\n");
            }
            out.append("</tbody></table>\n");
        }
        return out.append("</body></html>\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static List<String> columns(Map<String, Object> result) {
        return result.get("columns") instanceof List ? (List<String>) result.get("columns") : Collections.<String>emptyList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result) {
        return result.get("rows") instanceof List ? (List<Map<String, Object>>) result.get("rows")
            : Collections.<Map<String, Object>>emptyList();
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /** A value as one cell's text: a list or an object as its JSON, a number as written, empty for none. */
    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map || value instanceof Collection) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException unwritable) {
                return String.valueOf(value);
            }
        }
        String text = Values.text(value);
        return text == null ? "" : text;
    }

    /** A cell's text for a Markdown table: escaped, its | escaped, its line breaks a br. */
    static String mdCell(String text) {
        return mdText(text).replace("|", "\\|").replace("\r\n", "<br>").replace("\n", "<br>").replace("\r", "<br>");
    }

    /** Text that never turns into Markdown of its own. */
    static String mdText(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '\\' || c == '*' || c == '_' || c == '`' || c == '[' || c == ']' || c == '<') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            switch (c) {
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '&':
                    out.append("&amp;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&#39;");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }
}
