package process.pipeline.tasks;

import process.pipeline.StepContext;
import process.pipeline.data.Values;
import process.util.BusinessTime;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {{placeholders}} in a step's text settings (MIG-231): a bucket key, a notice's title, an API variable. The run's are
 * {{run}}, {{attempt}}, {{step}}, {{job}}, {{pipeline}}, {{date}} (yyyy-MM-dd) and {{time}} (HHmmss) on the business
 * clock (America/Chicago), and {{rows}} where a step has rows; for a run started for a file (an inbox arrival, a form
 * submission, an API run) {{input_key}} (its key in the workspace's storage) and {{input_name}} (the key's last part);
 * a row's are its columns, {{column}}. An unknown
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
        String inputKey = context.inputKey();
        if (inputKey != null && !inputKey.isEmpty()) {
            values.put("input_key", inputKey);
            values.put("input_name", inputKey.substring(inputKey.lastIndexOf('/') + 1));
        }
        return values;
    }

    static String fill(String template, Map<String, Object> values) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = values.containsKey(name) ? String.valueOf(Values.text(values.get(name)) == null ? ""
                : Values.text(values.get(name))) : matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
