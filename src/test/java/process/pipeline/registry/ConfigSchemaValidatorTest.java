package process.pipeline.registry;

import org.junit.jupiter.api.Test;
import process.pipeline.DefinitionProblem;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static process.pipeline.Definitions.config;

/** MIG-231: a step's config against its task's config schema -- every problem, each at its path. */
class ConfigSchemaValidatorTest {

    private static final Map<String, Object> SCHEMA = JsonSchema.object()
        .required("name", JsonSchema.string().minLength(1).maxLength(5))
        .property("count", JsonSchema.integer().minimum(1).maximum(10))
        .property("mode", JsonSchema.string().enumOf("a", "b"))
        .property("code", JsonSchema.string().pattern("^[a-z]+$").description("lower case letters"))
        .property("with", JsonSchema.string().format("step"))
        .property("vars", JsonSchema.map(JsonSchema.string()))
        .property("value", JsonSchema.scalar())
        .required("items", JsonSchema.array(JsonSchema.object().required("target", JsonSchema.string())).minItems(1).maxItems(3))
        .toMap();

    private static List<DefinitionProblem> problems(Object config) {
        return ConfigSchemaValidator.problems(SCHEMA, config, new HashSet<>(Collections.singletonList("read")));
    }

    @Test
    void aConfigThatHoldsHasNoProblems() {
        assertThat(problems(config("name", "ab", "count", 3, "mode", "a", "code", "xy", "with", "read", "vars", config("k", "v"),
            "value", null, "items", Collections.singletonList(config("target", "t"))))).isEmpty();
        assertThat(problems(config("name", "ab", "count", 3.0, "items", Collections.singletonList(config("target", "t")))))
            .as("a whole number written as 3.0 is a whole number").isEmpty();
    }

    @Test
    void everyProblemAtItsPath() {
        assertThat(problems(config("name", " ", "count", 11, "mode", "c", "code", "X1", "with", "later", "vars", config("k", 1),
            "value", Arrays.asList(1), "items", Arrays.asList(config("target", 2), "no", config()), "other", true))).containsExactly(
            new DefinitionProblem("name", "must not be empty"),
            new DefinitionProblem("count", "at most 10"),
            new DefinitionProblem("mode", "one of [a, b]"),
            new DefinitionProblem("code", "is not in the expected form: lower case letters"),
            new DefinitionProblem("with", "'later' is not an earlier step"),
            new DefinitionProblem("vars.k", "must be text"),
            new DefinitionProblem("value", "must be text, a number, true/false or null"),
            new DefinitionProblem("items[0].target", "must be text"),
            new DefinitionProblem("items[1]", "must be an object"),
            new DefinitionProblem("items[2].target", "required"),
            new DefinitionProblem("other", "unknown setting 'other'"));
    }

    @Test
    void requiredListsAndTypes() {
        assertThat(problems(config())).containsExactly(new DefinitionProblem("name", "required"), new DefinitionProblem("items", "required"));
        assertThat(problems(config("name", "toolong", "count", 1.5, "items", Collections.emptyList()))).containsExactly(
            new DefinitionProblem("name", "at most 5 characters"),
            new DefinitionProblem("count", "must be a whole number"),
            new DefinitionProblem("items", "at least one item"));
        assertThat(problems("text")).containsExactly(new DefinitionProblem("$", "must be an object"));
    }
}
