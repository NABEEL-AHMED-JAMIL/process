package process.pipeline;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@link StepContext} accessor reaches the run, whichever context a task is handed. A streaming task's in-memory
 * path ({@link Streams#inMemory}) wraps the step's context: an accessor it did not pass on fell back to the interface's
 * "outside a run" default (the run's model profile, its {{param.*}} values, the documents that started it). Both checks
 * read the interface's methods by reflection, so an accessor added later cannot be forgotten.
 */
class StepContextDelegationTest {

    /** MIG-421: the one report the run's context answers keptReport with (the same object, so the wrapper's answer is it). */
    private static final Optional<StepContext.KeptReport> KEPT = Optional.of(new StepContext.KeptReport("R-1", 3, "RPT-0018", "r.pdf", 2, 1));

    /** Every method of StepContext a task may call (none of Object's, no static helper). */
    private static List<Method> accessors() {
        List<Method> methods = new ArrayList<>();
        for (Method m : StepContext.class.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) && m.getDeclaringClass() != Object.class) {
                methods.add(m);
            }
        }
        methods.sort((a, b) -> a.toString().compareTo(b.toString()));
        assertThat(methods).extracting(Method::getName).contains("modelProfile", "runValues", "documents", "documentValues", "configAsWritten");
        return methods;
    }

    /** A value of {@code type} that only {@code m} answers, so a default or another accessor's answer cannot pass for it. */
    private static Object answerOf(Method m) {
        Class<?> type = m.getReturnType();
        String name = m.getName();
        if (type == void.class) {
            return null;
        }
        if (type == long.class || type == Long.class) {
            return 9_000L + name.hashCode() % 1000;
        }
        if (type == int.class || type == Integer.class) {
            return 700 + Math.abs(name.hashCode() % 100);
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.TRUE;
        }
        if (type == String.class) {
            return "from-the-run:" + name;
        }
        if (type == Map.class) {
            return Collections.singletonMap(name, "from-the-run");
        }
        if (type == List.class) {
            return Collections.singletonList("from-the-run:" + name);
        }
        if (type == Optional.class) {
            // MIG-421: a report kept for an item.
            return KEPT;
        }
        if (type == Dataset.class) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("asked", name);
            return Dataset.of(Collections.singletonList(row));
        }
        throw new AssertionError("Give the delegation test an answer for " + type.getName() + " (" + m + ").");
    }

    /** An argument of {@code type} for a call, so the delegate can be checked to have been handed the same one. */
    private static Object argumentOf(Class<?> type, int index) {
        if (type == long.class || type == Long.class) {
            return 31L + index;
        }
        if (type == int.class || type == Integer.class) {
            return 41 + index;
        }
        if (type == String.class) {
            return "arg-" + index;
        }
        if (type == byte[].class) {
            return new byte[] {1, 2, 3};
        }
        if (type == List.class) {
            return Collections.singletonList("col-" + index);
        }
        if (type == RunOutput.class) {
            return RunOutput.file("kept.csv", "csv", 1, 3);
        }
        if (type == StepContext.KeptReport.class) {
            return new StepContext.KeptReport("R" + index, 1, "RPT-" + index, "r.pdf", 1, 1);
        }
        if (type == Map.class) {
            // MIG-420: a row, whose item the run's tracker names.
            return Collections.singletonMap("key", "row-" + index);
        }
        throw new AssertionError("Give the delegation test an argument for " + type.getName() + ".");
    }

    /** A step's context that answers each accessor with its own value and records every call it is handed. */
    private static final class Recording implements InvocationHandler {
        final List<String> calls = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    default: return "Recording StepContext";
                }
            }
            this.calls.add(call(method, args));
            return answerOf(method);
        }
    }

    private static String call(Method m, Object[] args) {
        return m.getName() + Arrays.deepToString(args == null ? new Object[0] : args);
    }

    @Test
    void theInMemoryWrapperPassesOnEveryAccessorOfTheStepsContext() throws Exception {
        Recording recording = new Recording();
        StepContext run = (StepContext) Proxy.newProxyInstance(StepContext.class.getClassLoader(), new Class<?>[] {StepContext.class}, recording);
        List<String> wrong = new ArrayList<>();
        StreamingStepTask task = new StreamingStepTask() {
            @Override public String code() { return "delegation_probe"; }

            @Override public String description() { return "Calls every StepContext accessor."; }

            @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }

            @Override
            public StepResult stream(StreamContext context) throws Exception {
                for (Method m : accessors()) {
                    Object[] args = new Object[m.getParameterCount()];
                    for (int i = 0; i < args.length; i++) {
                        args[i] = argumentOf(m.getParameterTypes()[i], i);
                    }
                    int before = recording.calls.size();
                    Object answered;
                    try {
                        answered = m.invoke(context, args);
                    } catch (InvocationTargetException failed) {
                        wrong.add(m + " threw " + failed.getCause());
                        continue;
                    }
                    String expected = call(m, args);
                    boolean reached = recording.calls.subList(before, recording.calls.size()).contains(expected);
                    if (!reached) {
                        wrong.add(m.getName() + " did not reach the step's context");
                    } else if (!Objects.equals(answered, answerOf(m)) && !(answered instanceof Dataset)) {
                        wrong.add(m.getName() + " answered " + answered + ", not the run's " + answerOf(m));
                    }
                }
                return StepResult.nothing(0L);
            }
        };

        Streams.inMemory(task, run);

        assertThat(wrong).as("StepContext accessors the in-memory streaming wrapper (Streams.InMemory) does not pass on").isEmpty();
    }

    /**
     * The engine's own context answers every accessor itself: a new one with an interface default ("null outside a run")
     * must be decided for a run too, not inherited by accident. StreamTry extends it, so streamed steps see the same.
     */
    @Test
    void theEnginesContextAnswersEveryAccessorItself() throws Exception {
        Class<?> context = Arrays.stream(StepEngine.class.getDeclaredClasses()).filter(c -> c.getSimpleName().equals("Context")).findFirst()
            .orElseThrow(() -> new AssertionError("StepEngine.Context is gone; point this test at what a task is handed."));
        assertThat(StepContext.class.isAssignableFrom(context)).isTrue();
        List<String> inherited = new ArrayList<>();
        for (Method m : accessors()) {
            try {
                context.getDeclaredMethod(m.getName(), m.getParameterTypes());
            } catch (NoSuchMethodException missing) {
                inherited.add(m.getName());
            }
        }
        assertThat(inherited).as("StepContext accessors StepEngine.Context leaves to the interface's outside-a-run default").isEmpty();
        Class<?> streamTry = Arrays.stream(StepEngine.class.getDeclaredClasses()).filter(c -> c.getSimpleName().equals("StreamTry")).findFirst()
            .orElseThrow(AssertionError::new);
        assertThat(streamTry.getSuperclass()).isEqualTo(context);
    }
}
