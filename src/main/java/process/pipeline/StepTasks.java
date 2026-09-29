package process.pipeline;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The step tasks this Core knows, by code (MIG-230): every {@link StepTask} bean. The Task Registry (MIG-231) grows
 * this list; a definition that names a code not on it does not validate, and a run pinned to a definition whose task
 * has since gone is declined, not guessed at.
 */
@Component
public class StepTasks {

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private final Map<String, StepTask> byCode = new LinkedHashMap<>();

    public StepTasks(List<StepTask> tasks) {
        List<StepTask> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator.comparing(StepTask::code));
        for (StepTask task : sorted) {
            if (task.code() == null || !CODE.matcher(task.code()).matches()) {
                throw new IllegalStateException("A step task's code is lower case letters, digits and '_': " + task.code());
            }
            if (this.byCode.put(task.code(), task) != null) {
                throw new IllegalStateException("Two step tasks share the code " + task.code());
            }
        }
    }

    public Optional<StepTask> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(this.byCode.get(code.trim()));
    }

    public List<StepTask> all() {
        return Collections.unmodifiableList(new ArrayList<>(this.byCode.values()));
    }
}
