package process.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/** MIG-347: run parameters -- what a pipeline may declare, what a run's values become, and how they fill a step's settings. */
class RunParametersTest {

    static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    static PipelineDefinition.Parameter p(String name, String type) {
        return PipelineDefinition.Parameter.of(name, type);
    }

    static PipelineDefinition.Parameter choice(String name, String... choices) {
        PipelineDefinition.Parameter c = p(name, RunParameters.CHOICE);
        c.setChoices(Arrays.asList(choices));
        return c;
    }

    // ---- declarations ---------------------------------------------------------------------------------------

    @Test
    void aWellFormedDeclarationHasNoProblems() {
        PipelineDefinition.Parameter since = p("since", RunParameters.DATE);
        since.setRequired(true);
        PipelineDefinition.Parameter limit = p("max_rows", RunParameters.NUMBER);
        limit.setDefaultValue(1000);
        PipelineDefinition.Parameter region = choice("region", "north", "south");
        region.setDefaultValue("north");
        assertThat(RunParameters.problems(Arrays.asList(since, limit, region, p("dry_run", RunParameters.BOOLEAN),
            p("note", RunParameters.TEXT), p("at", RunParameters.DATETIME)))).isEmpty();
    }

    @Test
    void namesTypesChoicesAndDefaultsAreCheckedEachAtItsPath() {
        PipelineDefinition.Parameter badDefault = p("since", RunParameters.DATE);
        badDefault.setDefaultValue("yesterday");
        PipelineDefinition.Parameter noChoices = p("region", RunParameters.CHOICE);
        PipelineDefinition.Parameter choicesOnText = p("note", RunParameters.TEXT);
        choicesOnText.setChoices(Collections.singletonList("a"));
        List<DefinitionProblem> problems = RunParameters.problems(Arrays.asList(p("Bad Name", RunParameters.TEXT), p("ok", "money"),
            badDefault, noChoices, choicesOnText, p("ok2", RunParameters.TEXT), p("ok2", RunParameters.TEXT), choice("dup", "x", "x")));
        assertThat(problems).extracting(DefinitionProblem::getPath).containsExactly("parameters[0].name", "parameters[1].type",
            "parameters[2].default", "parameters[3].choices", "parameters[4].choices", "parameters[6].name", "parameters[7].choices[1]");
    }

    @Test
    void atMostTwentyParameters() {
        PipelineDefinition.Parameter[] many = new PipelineDefinition.Parameter[21];
        for (int i = 0; i < many.length; i++) {
            many[i] = p("p" + i, RunParameters.TEXT);
        }
        assertThat(RunParameters.problems(Arrays.asList(many))).extracting(DefinitionProblem::getPath).containsExactly("parameters");
    }

    // ---- values ---------------------------------------------------------------------------------------------

    @Test
    void eachTypeIsNormalisedToOneTextForm() {
        PipelineDefinition.Parameter n = p("n", RunParameters.NUMBER);
        assertThat(RunParameters.normalise(n, "1.500")).isEqualTo("1.5");
        assertThat(RunParameters.normalise(n, 42)).isEqualTo("42");
        assertThat(RunParameters.normalise(n, "-0.0")).isEqualTo("0");
        assertThat(RunParameters.normalise(n, "1e3")).isEqualTo("1000");
        assertThat(RunParameters.normalise(p("b", RunParameters.BOOLEAN), "TRUE")).isEqualTo("true");
        assertThat(RunParameters.normalise(p("b", RunParameters.BOOLEAN), false)).isEqualTo("false");
        assertThat(RunParameters.normalise(p("d", RunParameters.DATE), " 2026-02-28 ")).isEqualTo("2026-02-28");
        assertThat(RunParameters.normalise(p("t", RunParameters.DATETIME), "2026-10-01T09:30")).isEqualTo("2026-10-01T09:30:00");
        assertThat(RunParameters.normalise(choice("c", "north", "south"), "south")).isEqualTo("south");
        assertThat(RunParameters.normalise(p("x", RunParameters.TEXT), "O'Brien, Ward 3")).isEqualTo("O'Brien, Ward 3");
    }

    @Test
    void aValueOfTheWrongTypeSaysWhatTheTypeWants() {
        assertThatThrownBy(() -> RunParameters.normalise(p("n", RunParameters.NUMBER), "ten")).hasMessage("a number");
        assertThatThrownBy(() -> RunParameters.normalise(p("n", RunParameters.NUMBER), true)).hasMessage("a number");
        assertThatThrownBy(() -> RunParameters.normalise(p("b", RunParameters.BOOLEAN), "yes")).hasMessage("true or false");
        assertThatThrownBy(() -> RunParameters.normalise(p("d", RunParameters.DATE), "2026-02-30")).hasMessage("a date, as 2026-10-01");
        assertThatThrownBy(() -> RunParameters.normalise(p("t", RunParameters.DATETIME), "9:30")).hasMessageContaining("a date and time");
        assertThatThrownBy(() -> RunParameters.normalise(choice("c", "north"), "east")).hasMessage("one of north");
        assertThatThrownBy(() -> RunParameters.normalise(p("x", RunParameters.TEXT), "{{param.other}}")).hasMessage("text without '{{'");
        assertThatThrownBy(() -> RunParameters.normalise(p("x", RunParameters.TEXT), "two\nlines")).hasMessage("one line of text");
        char[] long501 = new char[501];
        Arrays.fill(long501, 'a');
        assertThatThrownBy(() -> RunParameters.normalise(p("x", RunParameters.TEXT), new String(long501))).hasMessage("at most 500 characters");
    }

    @Test
    void defaultsFillWhatIsNotGivenAndARequiredOneWithoutEitherIsRefused() {
        PipelineDefinition.Parameter region = choice("region", "north", "south");
        region.setDefaultValue("north");
        PipelineDefinition.Parameter since = p("since", RunParameters.DATE);
        since.setRequired(true);
        PipelineDefinition.Parameter note = p("note", RunParameters.TEXT);
        List<PipelineDefinition.Parameter> declared = Arrays.asList(region, since, note);

        RunParameters.Resolved none = RunParameters.resolve(declared, null);
        assertThat(none.ok()).isFalse();
        assertThat(none.problems).containsExactly("since: required, and it has no default");

        Map<String, Object> given = new HashMap<>();
        given.put("since", "2026-10-01");
        RunParameters.Resolved some = RunParameters.resolve(declared, given);
        assertThat(some.ok()).isTrue();
        assertThat(some.values).containsExactly(entry("region", "north"),
            entry("since", "2026-10-01"));

        given.put("region", "east");
        given.put("unknown", 1);
        RunParameters.Resolved wrong = RunParameters.resolve(declared, given);
        assertThat(wrong.problems).containsExactly("unknown: this pipeline has no such parameter", "region: one of north, south");
        assertThat(wrong.sentence()).isEqualTo("unknown: this pipeline has no such parameter; region: one of north, south.");
    }

    @Test
    void aBlankValueTakesTheDefaultButBlankTextIsAValue() {
        PipelineDefinition.Parameter n = p("n", RunParameters.NUMBER);
        n.setDefaultValue(5);
        PipelineDefinition.Parameter t = p("t", RunParameters.TEXT);
        t.setDefaultValue("fallback");
        Map<String, Object> given = new HashMap<>();
        given.put("n", " ");
        given.put("t", "");
        assertThat(RunParameters.resolve(Arrays.asList(n, t), given).values).containsEntry("n", "5").containsEntry("t", "");
    }

    // ---- run values -----------------------------------------------------------------------------------------

    @Test
    void aDayInChicagoIsTwentyThreeHoursInSpringAndTwentyFiveInAutumn() {
        RunParameters.RunValues spring = RunParameters.RunValues.ofDay(null, LocalDate.parse("2026-03-08"), CHICAGO);
        assertThat(spring.windowStart).isEqualTo("2026-03-08T00:00:00-06:00");
        assertThat(spring.windowEnd).isEqualTo("2026-03-09T00:00:00-05:00");
        RunParameters.RunValues autumn = RunParameters.RunValues.ofDay(null, LocalDate.parse("2026-11-01"), CHICAGO);
        assertThat(autumn.windowStart).isEqualTo("2026-11-01T00:00:00-05:00");
        assertThat(autumn.windowEnd).isEqualTo("2026-11-02T00:00:00-06:00");
        RunParameters.RunValues monthEnd = RunParameters.RunValues.ofDay(null, LocalDate.parse("2026-02-28"), CHICAGO);
        assertThat(monthEnd.windowEnd).isEqualTo("2026-03-01T00:00:00-06:00");
    }

    @Test
    void anHourIsALocalHourAndTheDayIsTheZones() {
        // 2026-10-01T04:30Z is 23:30 the day before in Chicago, and 10:00 that day in Kolkata.
        Instant at = Instant.parse("2026-10-01T04:30:00Z");
        RunParameters.RunValues chicago = RunParameters.RunValues.ofHour(null, at, CHICAGO);
        assertThat(chicago.date).isEqualTo("2026-09-30");
        assertThat(chicago.windowStart).isEqualTo("2026-09-30T23:00:00-05:00");
        RunParameters.RunValues kolkata = RunParameters.RunValues.ofHour(null, at, ZoneId.of("Asia/Kolkata"));
        assertThat(kolkata.date).isEqualTo("2026-10-01");
        assertThat(kolkata.windowStart).isEqualTo("2026-10-01T10:00:00+05:30");
        assertThat(kolkata.windowEnd).isEqualTo("2026-10-01T11:00:00+05:30");
    }

    @Test
    void runValuesGoThroughJobQueueAndBack() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("region", "north");
        RunParameters.RunValues values = RunParameters.RunValues.ofDay(params, LocalDate.parse("2026-10-05"), CHICAGO);
        RunParameters.Stored read = RunParameters.read(values.toJson());
        assertThat(read.params).containsEntry("region", "north");
        assertThat(read.run.date).isEqualTo("2026-10-05");
        assertThat(read.run.windowStart).isEqualTo("2026-10-05T00:00:00-05:00");
        assertThat(read.run.zone).isEqualTo("America/Chicago");
        RunParameters.Stored onlyParams = RunParameters.read(new RunParameters.RunValues(params, null, null, null, null).toJson());
        assertThat(onlyParams.run).isNull();
        assertThat(onlyParams.params).containsEntry("region", "north");
        assertThat(RunParameters.read("not json").params).isEmpty();
        assertThat(RunParameters.read(null)).isSameAs(RunParameters.Stored.NONE);
    }

    // ---- filling --------------------------------------------------------------------------------------------

    @Test
    void parametersAndRunValuesFillEveryTextAtAnyDepthAndLeaveOtherPlaceholders() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("region", "north");
        Map<String, Object> values = RunParameters.RunValues.ofDay(params, LocalDate.parse("2026-10-05"), CHICAGO).placeholders();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("key", "exports/{{param.region}}/{{run.date}}/{{run}}.csv");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("variables", Collections.singletonMap("since", "{{ run.windowStart }}"));
        request.put("list", Arrays.asList("{{run.windowEnd}}", 3, "{{column}}"));
        config.put("request", request);
        config.put("rows", 10);
        config.put("missing", "{{param.nobody}}");
        Map<String, Object> filled = RunParameters.fill(config, values, Collections.<String>emptySet());
        assertThat(filled.get("key")).isEqualTo("exports/north/2026-10-05/{{run}}.csv");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) filled.get("request");
        assertThat(r.get("variables")).isEqualTo(Collections.singletonMap("since", "2026-10-05T00:00:00-05:00"));
        assertThat(r.get("list")).isEqualTo(Arrays.asList("2026-10-06T00:00:00-05:00", 3, "{{column}}"));
        assertThat(filled.get("rows")).isEqualTo(10);
        assertThat(filled.get("missing")).isEqualTo("{{param.nobody}}");
        // The step's own config is not changed.
        assertThat(config.get("key")).isEqualTo("exports/{{param.region}}/{{run.date}}/{{run}}.csv");
    }

    @Test
    void aConfigWithoutRunPlaceholdersIsHandedBackAsItIs() {
        Map<String, Object> config = Collections.singletonMap("key", "exports/{{date}}/{{run}}.csv");
        assertThat(RunParameters.fill(config, Collections.<String, Object>singletonMap("run.date", "2026-10-05"), null)).isSameAs(config);
    }

    @Test
    void inASqlSettingQuotesAreDoubledAndABackslashIsRefused() {
        Map<String, Object> values = new HashMap<>();
        values.put("param.name", "O'Brien");
        values.put("param.path", "a\\b");
        Map<String, Object> config = new HashMap<>();
        config.put("query", "SELECT * FROM visits WHERE surname = '{{param.name}}'");
        config.put("title", "{{param.name}}");
        Map<String, Object> filled = RunParameters.fill(config, values, Collections.singleton("query"));
        assertThat(filled.get("query")).isEqualTo("SELECT * FROM visits WHERE surname = 'O''Brien'");
        assertThat(filled.get("title")).isEqualTo("O'Brien");
        Map<String, Object> slashed = Collections.singletonMap("query", "SELECT '{{param.path}}'");
        assertThatThrownBy(() -> RunParameters.fill(slashed, values, Collections.singleton("query")))
            .hasMessageContaining("{{param.path}} has a backslash");
    }

    @Test
    void theSqlSettingsAreWhatATasksSchemaMarksSql() {
        Map<String, Object> schema = new HashMap<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Collections.singletonMap("format", "sql"));
        properties.put("maxRows", Collections.singletonMap("type", "integer"));
        schema.put("properties", properties);
        assertThat(RunParameters.sqlFields(schema)).containsExactly("query");
        assertThat(RunParameters.sqlFields(null)).isEmpty();
    }

    @Test
    void theParametersAStepNamesAreFoundAtAnyDepth() {
        Map<String, Object> config = new HashMap<>();
        config.put("a", "{{param.one}} and {{param.two}}");
        config.put("b", Collections.singletonList(Collections.singletonMap("c", "{{ param.three }}")));
        config.put("d", "{{run.date}} {{column}}");
        assertThat(RunParameters.namedParameters(config)).containsExactly("one", "three", "two");
    }
}
