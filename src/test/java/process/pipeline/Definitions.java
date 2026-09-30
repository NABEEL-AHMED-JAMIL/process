package process.pipeline;

import process.pipeline.tasks.LegacyStepTask;
import process.pipeline.tasks.SampleStepTask;
import process.pipeline.tasks.SelectStepTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test helpers: the registered tasks as the application has them, and definitions built in a line. */
public final class Definitions {

    private Definitions() {
    }

    public static StepTasks builtInTasks(StepTask... more) {
        List<StepTask> tasks = new ArrayList<>(Arrays.asList(new LegacyStepTask(), new SampleStepTask(), new SelectStepTask()));
        tasks.addAll(Arrays.asList(more));
        return new StepTasks(tasks);
    }

    public static PipelineDefinition of(PipelineDefinition.Step... steps) {
        PipelineDefinition definition = new PipelineDefinition();
        definition.setVersion(1);
        definition.setSteps(new ArrayList<>(Arrays.asList(steps)));
        return definition;
    }

    public static PipelineDefinition.Step step(String key, String task) {
        PipelineDefinition.Step step = new PipelineDefinition.Step();
        step.setKey(key);
        step.setTask(task);
        return step;
    }

    public static PipelineDefinition.Step step(String key, String task, Map<String, Object> config) {
        PipelineDefinition.Step step = step(key, task);
        step.setConfig(config);
        return step;
    }

    public static Map<String, Object> config(Object... keyValues) {
        Map<String, Object> config = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            config.put((String) keyValues[i], keyValues[i + 1]);
        }
        return config;
    }

    public static Map<String, Object> row(Object... keyValues) {
        return config(keyValues);
    }

    // The array is only copied into a new list; nothing is stored in it.
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static PipelineDefinition.Step sample(String key, Map<String, Object>... rows) {
        return step(key, "sample", config("rows", new ArrayList<>(Arrays.asList(rows))));
    }
}
