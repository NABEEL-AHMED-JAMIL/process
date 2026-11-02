package process.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MIG-450: a Route step splits its rows into named branches; how the rest of a definition reads them.
 *
 * <pre>
 * - key: triage                      # task: route
 *   config: {branches: [{name: urgent, conditions: [...]}], otherwise: routine, column: branch}
 * - key: second_opinion              # the first step of a branch reads "&lt;route&gt;.&lt;branch&gt;" ...
 *   input: triage.urgent
 * - key: shape                       # ... and the steps after it, by default, the step before them: still in branch urgent
 * - key: together                    # task: merge -- every branch's last output, stacked; the steps after it are one line again
 *   config: {route: triage}
 * </pre>
 *
 * A step is in a branch when what it reads is: its input (named, or by default the output before it) is a branch of a
 * route, or a step in that branch. A Merge ends the branches of its route. A branch with no step passes its rows
 * through, so a Merge stacks the branch's own rows. The rules here are the engine's, the validator's and the console's.
 */
public final class Routes {

    public static final String ROUTE = "route";
    public static final String MERGE = "merge";
    public static final String DEFAULT_OTHERWISE = "otherwise";
    public static final String DEFAULT_COLUMN = "branch";

    private Routes() {
    }

    public static boolean isRoute(PipelineDefinition.Step step) {
        return step != null && ROUTE.equals(step.getTask());
    }

    public static boolean isMerge(PipelineDefinition.Step step) {
        return step != null && MERGE.equals(step.getTask());
    }

    /** A route's branch names, in order: its condition branches, then its Otherwise. */
    public static List<String> branchNames(PipelineDefinition.Step route) {
        List<String> names = new ArrayList<>();
        Object branches = route.effectiveConfig().get("branches");
        if (branches instanceof List) {
            for (Object branch : (List<?>) branches) {
                if (branch instanceof Map && ((Map<?, ?>) branch).get("name") instanceof String) {
                    names.add(((String) ((Map<?, ?>) branch).get("name")).trim());
                }
            }
        }
        names.add(otherwise(route));
        return names;
    }

    public static String otherwise(PipelineDefinition.Step route) {
        Object name = route.effectiveConfig().get("otherwise");
        return name instanceof String && !((String) name).trim().isEmpty() ? ((String) name).trim() : DEFAULT_OTHERWISE;
    }

    /** The column a route writes each row's branch into (and a Merge keeps). */
    public static String column(PipelineDefinition.Step route) {
        Object name = route.effectiveConfig().get("column");
        return name instanceof String && !((String) name).trim().isEmpty() ? ((String) name).trim() : DEFAULT_COLUMN;
    }

    /** "&lt;route&gt;.&lt;branch&gt;": how a step names a branch's rows. */
    public static String ref(String routeKey, String branch) {
        return routeKey + "." + branch;
    }

    /** The branch refs a route makes, in order. */
    public static List<String> refs(PipelineDefinition.Step route) {
        List<String> refs = new ArrayList<>();
        for (String name : branchNames(route)) {
            refs.add(ref(route.getKey(), name));
        }
        return refs;
    }

    /**
     * Which branch each step is in ("&lt;route&gt;.&lt;branch&gt;"), for the steps that are in one: what it reads is a branch,
     * or a step in a branch. A Merge and the steps after it (reading it) are in none.
     */
    public static Map<String, String> branchOfSteps(List<PipelineDefinition.Step> steps) {
        Map<String, String> of = new LinkedHashMap<>();
        Map<String, String> refs = new LinkedHashMap<>();
        String previous = null;
        for (PipelineDefinition.Step step : steps) {
            if (step == null || step.getKey() == null) {
                continue;
            }
            String reads = step.getInput() != null ? step.getInput() : previous;
            String branch = reads == null ? null : refs.containsKey(reads) ? reads : of.get(reads);
            if (branch != null && !isMerge(step)) {
                of.put(step.getKey(), branch);
            }
            if (isRoute(step)) {
                for (String ref : refs(step)) {
                    refs.put(ref, step.getKey());
                }
            }
            previous = step.getKey();
        }
        return of;
    }

    /** The route step a definition names by key, or null. */
    public static PipelineDefinition.Step route(List<PipelineDefinition.Step> steps, String key) {
        for (PipelineDefinition.Step step : steps) {
            if (step != null && key != null && key.equals(step.getKey()) && isRoute(step)) {
                return step;
            }
        }
        return null;
    }

    /** One branch's last output before a Merge: its name and what to read (a step's key, or the branch's own rows). */
    public static final class Head {
        public final String branch;
        public final String reads;
        /** The route's branch column. */
        public final String column;

        Head(String branch, String reads, String column) {
            this.branch = branch;
            this.reads = reads;
            this.column = column;
        }
    }

    /**
     * What a Merge at {@code mergeIndex} stacks for route {@code routeKey}: for each branch, its last step before the
     * Merge that has an output ({@code available}), else the branch's own rows.
     */
    public static List<Head> heads(List<PipelineDefinition.Step> steps, int mergeIndex, String routeKey, Set<String> available) {
        PipelineDefinition.Step route = route(steps, routeKey);
        if (route == null) {
            return Collections.emptyList();
        }
        Map<String, String> branchOf = branchOfSteps(steps);
        List<Head> heads = new ArrayList<>();
        for (String name : branchNames(route)) {
            String ref = ref(routeKey, name);
            String reads = ref;
            for (int i = 0; i < mergeIndex && i < steps.size(); i++) {
                PipelineDefinition.Step step = steps.get(i);
                if (step != null && ref.equals(branchOf.get(step.getKey())) && available.contains(step.getKey())) {
                    reads = step.getKey();
                }
            }
            heads.add(new Head(name, reads, column(route)));
        }
        return heads;
    }
}
