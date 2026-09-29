package process.pipeline;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;
import static process.pipeline.Definitions.sample;
import static process.pipeline.Definitions.step;

/**
 * MIG-230: a definition is JSON, and YAML is a view of the same definition -- editing either saves the same thing
 * (MIG-249's acceptance) -- and a definition that cannot be read says where, never silently drops what it did not know.
 */
class DefinitionCodecTest {

    private static final String YAML = String.join("\n",
        "version: 1",
        "source:",
        "  type: task",
        "steps:",
        "  - key: read",
        "    name: Sample rows",
        "    task: sample",
        "    config:",
        "      rows:",
        "        - id: 1",
        "          name: Ada",
        "          code: \"007\"",
        "          active: true",
        "        - id: 2",
        "          name: \"true\"",
        "          code: \"null\"",
        "          active: false",
        "    retry:",
        "      maxAttempts: 2",
        "      delaySeconds: 5",
        "    timeoutSeconds: 60",
        "    onError: fail",
        "  - key: shape",
        "    task: select",
        "    input: read",
        "    config:",
        "      columns:",
        "        id: patient_id",
        "        name: 'name: first'",
        "    onError: skip_rest",
        "settings:",
        "  datasetRetentionHours: 12",
        "");

    @Test
    void yamlAndJsonAreTwoViewsOfOneDefinition() throws Exception {
        PipelineDefinition fromYaml = DefinitionCodec.fromYaml(YAML);
        String json = DefinitionCodec.toJson(fromYaml);
        PipelineDefinition fromJson = DefinitionCodec.fromJson(json);

        assertThat(DefinitionCodec.toJson(fromJson)).isEqualTo(json);
        assertThat(DefinitionCodec.toYaml(fromJson)).isEqualTo(DefinitionCodec.toYaml(fromYaml));
        assertThat(DefinitionCodec.toJson(DefinitionCodec.fromYaml(DefinitionCodec.toYaml(fromJson)))).isEqualTo(json);
        assertThat(DefinitionCodec.toJson(DefinitionCodec.fromJson(DefinitionCodec.toPrettyJson(fromJson)))).isEqualTo(json);
    }

    @Test
    void valuesKeepTheirTypesThroughYaml() throws Exception {
        PipelineDefinition definition = DefinitionCodec.fromYaml(DefinitionCodec.toYaml(DefinitionCodec.fromYaml(YAML)));
        Object rows = definition.getSteps().get(0).getConfig().get("rows");
        assertThat(rows).isEqualTo(Arrays.asList(row("id", 1, "name", "Ada", "code", "007", "active", true),
            row("id", 2, "name", "true", "code", "null", "active", false)));
        assertThat(definition.getSteps().get(1).getConfig().get("columns")).isEqualTo(config("id", "patient_id", "name", "name: first"));
        assertThat(definition.getSteps().get(0).getRetry().getMaxAttempts()).isEqualTo(2);
        assertThat(definition.getSettings().getDatasetRetentionHours()).isEqualTo(12);
    }

    @Test
    void theStoredJsonKeepsTheDefinitionsOrderAndLeavesOutWhatWasNotSaid() throws Exception {
        PipelineDefinition definition = Definitions.of(sample("read", row("a", 1)), step("keep", "select", config("columns",
            Arrays.asList("a"))));
        assertThat(DefinitionCodec.toJson(definition)).isEqualTo("{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"sample\","
            + "\"config\":{\"rows\":[{\"a\":1}]}},{\"key\":\"keep\",\"task\":\"select\",\"config\":{\"columns\":[\"a\"]}}]}");
    }

    @Test
    void anUnknownFieldIsAProblemAtItsPathNotSilentlyDropped() {
        assertThatThrownBy(() -> DefinitionCodec.fromJson("{\"version\":1,\"steps\":[{\"key\":\"a\",\"task\":\"sample\","
            + "\"retry\":{\"max\":3}}]}"))
            .isInstanceOfSatisfying(DefinitionException.class, ex -> assertThat(ex.getProblems())
                .containsExactly(new DefinitionProblem("steps[0].retry", "unknown field 'max'")));
        assertThatThrownBy(() -> DefinitionCodec.fromYaml("version: 1\nstep: []\n"))
            .isInstanceOfSatisfying(DefinitionException.class, ex -> assertThat(ex.getProblems())
                .containsExactly(new DefinitionProblem("$", "unknown field 'step'")));
    }

    @Test
    void aValueOfTheWrongTypeIsAProblemAtItsPath() {
        assertThatThrownBy(() -> DefinitionCodec.fromJson("{\"version\":1,\"steps\":[{\"key\":\"a\",\"task\":\"sample\","
            + "\"timeoutSeconds\":\"soon\"}]}"))
            .isInstanceOfSatisfying(DefinitionException.class, ex -> assertThat(ex.getProblems())
                .containsExactly(new DefinitionProblem("steps[0].timeoutSeconds", "'soon' is not a whole number")));
        assertThatThrownBy(() -> DefinitionCodec.fromYaml("version: 1\nsteps: {key: a}\n"))
            .isInstanceOfSatisfying(DefinitionException.class, ex -> assertThat(ex.getProblems().get(0).getPath()).isEqualTo("steps"));
    }

    @Test
    void textThatIsNotJsonOrYamlSaysWhere() {
        assertThatThrownBy(() -> DefinitionCodec.fromJson("{\"version\": 1,"))
            .isInstanceOfSatisfying(DefinitionException.class, ex -> assertThat(ex.getProblems().get(0).getMessage())
                .startsWith("not valid JSON at line 1"));
        assertThatThrownBy(() -> DefinitionCodec.fromYaml("version: [1\n"))
            .isInstanceOfSatisfying(DefinitionException.class, ex -> assertThat(ex.getProblems().get(0).getMessage())
                .startsWith("not valid YAML"));
    }

    @Test
    void emptyOrOversizedIsRefusedBeforeItIsRead() {
        for (String empty : new String[] {null, "", "   \n"}) {
            assertThatThrownBy(() -> DefinitionCodec.fromYaml(empty)).isInstanceOfSatisfying(DefinitionException.class,
                ex -> assertThat(ex.getProblems()).containsExactly(new DefinitionProblem("$", "the definition is empty")));
        }
        StringBuilder big = new StringBuilder("version: 1\n# ");
        while (big.length() <= DefinitionCodec.MAX_CHARS) {
            big.append("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        }
        assertThatThrownBy(() -> DefinitionCodec.fromYaml(big.toString())).isInstanceOfSatisfying(DefinitionException.class,
            ex -> assertThat(ex.getProblems().get(0).getMessage()).contains("at most " + DefinitionCodec.MAX_CHARS));
    }

    /** The "billion laughs" needs anchors and aliases; a definition needs neither, nor tags, nor a second document. */
    @Test
    void yamlAnchorsAliasesTagsAndSecondDocumentsAreRefused() {
        String[][] refused = {
            {"version: 1\nsteps:\n  - &s {key: a, task: sample}\n  - *s\n", "YAML anchors (&name) are not accepted in a definition"},
            {"version: 1\nsettings: {defaultOnError: *x}\n", "YAML aliases (*name) are not accepted in a definition"},
            {"version: 1\nsteps: !!python/object:os.system {}\n", "YAML tags (!name) are not accepted in a definition"},
            {"version: 1\nsteps: !custom []\n", "YAML tags (!name) are not accepted in a definition"},
            {"version: 1\n---\nversion: 1\n", "a definition is one YAML document; found more than one"}};
        for (String[] yaml : refused) {
            assertThatThrownBy(() -> DefinitionCodec.fromYaml(yaml[0])).as(yaml[0]).isInstanceOfSatisfying(DefinitionException.class,
                ex -> assertThat(ex.getProblems().get(0).getMessage()).startsWith(yaml[1]));
        }
    }

    @Test
    void readTellsJsonFromYamlOrTakesTheFormatItIsTold() throws Exception {
        assertThat(DefinitionCodec.read("{\"version\":1}", null).getVersion()).isEqualTo(1);
        assertThat(DefinitionCodec.read("version: 1", null).getVersion()).isEqualTo(1);
        assertThat(DefinitionCodec.read("{\"version\":1}", "yaml").getVersion()).isEqualTo(1);
        assertThatThrownBy(() -> DefinitionCodec.read("version: 1", "json")).isInstanceOf(DefinitionException.class);
    }

    @Test
    void theLegacyWrapIsOneLegacyStepThatNamesItsPipeline() throws Exception {
        PipelineDefinition legacy = PipelineDefinition.legacy(" F768927 ");
        assertThat(legacy.isLegacy()).isTrue();
        assertThat(DefinitionCodec.toJson(legacy)).isEqualTo("{\"version\":1,\"source\":{\"type\":\"task\"},\"steps\":[{\"key\":\"legacy\","
            + "\"name\":\"Legacy pipeline F768927\",\"task\":\"legacy\",\"config\":{\"pipelineId\":\"F768927\"}}]}");
        assertThat(DefinitionCodec.fromYaml(DefinitionCodec.toYaml(legacy)).isLegacy()).isTrue();
        assertThat(Definitions.of(sample("a", row("x", 1))).isLegacy()).isFalse();
    }
}
