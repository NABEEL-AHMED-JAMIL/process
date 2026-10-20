package process.pipeline;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Jackson 2.15+ (2.18.11 since the vulnerabilities review of 2026-10-13) refuses, while it reads, a string longer than
 * 20,000,000 characters, nesting deeper than 1000 and a number longer than 1000 digits. A pipeline definition is the
 * largest JSON a person sends Core, so this reads the biggest one Core accepts the way Spring MVC reads the request
 * (Boot's Jackson2ObjectMapperBuilder, MappingJackson2HttpMessageConverter), then parses it as the service does
 * (DefinitionCodec, JSON and YAML -- SnakeYAML 2.4). DefinitionCodec's own 256 KB cap is far below Jackson's limits, so
 * nothing is raised in configuration; only a body past Jackson's limits is refused, as an unreadable request (400).
 */
@SuppressWarnings("removal") // Spring's Jackson 2 support / MockMvc client factory, kept on purpose (MIG-389)
class LargeJsonBodyTest {

    static final ObjectMapper MVC = Jackson2ObjectMapperBuilder.json().build();
    static final MappingJackson2HttpMessageConverter CONVERTER = new MappingJackson2HttpMessageConverter(MVC);

    static PipelineDefinitionService.DefinitionRequest read(String json) throws Exception {
        return (PipelineDefinitionService.DefinitionRequest) CONVERTER.read(PipelineDefinitionService.DefinitionRequest.class,
            new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8)));
    }

    static String request(String text, String format) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pipelineKey", 1L);
        body.put("format", format);
        body.put("text", text);
        return MVC.writeValueAsString(body);
    }

    /** A definition of sample steps with inline rows, and one step whose config nests {@code depth} deep. */
    static PipelineDefinition definition(int steps, int rowsPerStep, int depth) {
        List<PipelineDefinition.Step> all = new ArrayList<>();
        for (int s = 0; s < steps; s++) {
            List<Object> rows = new ArrayList<>();
            for (int r = 0; r < rowsPerStep; r++) {
                rows.add(Definitions.row("id", r, "name", "Patient " + r, "amount", "1234.50", "note", "n".repeat(40)));
            }
            all.add(Definitions.step("step_" + s, "sample", Definitions.config("rows", rows)));
        }
        Map<String, Object> nested = new LinkedHashMap<>();
        Map<String, Object> level = nested;
        for (int d = 0; d < depth; d++) {
            Map<String, Object> next = new LinkedHashMap<>();
            level.put("level", next);
            level = next;
        }
        all.add(Definitions.step("deep", "select", Definitions.config("nested", nested)));
        return Definitions.of(all.toArray(new PipelineDefinition.Step[0]));
    }

    @Test
    void theLargestDefinitionCoreAcceptsIsReadAndParsedWithTheDefaultLimits() throws Exception {
        String json = DefinitionCodec.toJson(definition(60, 30, 40));
        assertThat(json.length()).isBetween(DefinitionCodec.MAX_CHARS / 2, DefinitionCodec.MAX_CHARS);

        PipelineDefinitionService.DefinitionRequest in = read(request(json, "json"));
        PipelineDefinition parsed = DefinitionCodec.read(in.getText(), in.getFormat());
        assertThat(parsed.getSteps()).hasSize(61);

        // The same definition as YAML (SnakeYAML 2.4 since the review): written, read back, unchanged.
        String yaml = DefinitionCodec.toYaml(parsed);
        PipelineDefinition fromYaml = DefinitionCodec.read(read(request(yaml, "yaml")).getText(), "yaml");
        assertThat(DefinitionCodec.toJson(fromYaml)).isEqualTo(json);
    }

    @Test
    void aDefinitionPastCoresCapIsStillReadByJacksonAndRefusedByTheCodecWithItsOwnMessage() throws Exception {
        String json = DefinitionCodec.toJson(definition(200, 30, 1));
        assertThat(json.length()).isGreaterThan(DefinitionCodec.MAX_CHARS);

        PipelineDefinitionService.DefinitionRequest in = read(request(json, "json"));
        assertThatThrownBy(() -> DefinitionCodec.read(in.getText(), in.getFormat()))
            .isInstanceOfSatisfying(DefinitionException.class, ex -> assertThat(ex.getProblems().get(0).toString())
                .contains("at most " + DefinitionCodec.MAX_CHARS));
    }

    @Test
    void theLimitsAreJacksonsDefaultsNothingIsRaised() {
        StreamReadConstraints c = MVC.getFactory().streamReadConstraints();
        assertThat(c.getMaxStringLength()).isEqualTo(StreamReadConstraints.DEFAULT_MAX_STRING_LEN);
        assertThat(c.getMaxNestingDepth()).isEqualTo(StreamReadConstraints.DEFAULT_MAX_DEPTH);
        assertThat(c.getMaxNumberLength()).isEqualTo(StreamReadConstraints.DEFAULT_MAX_NUM_LEN);
    }

    @Test
    void aStringPastTwentyMillionCharactersIsAnUnreadableRequest() throws Exception {
        String json = request("{" + " ".repeat(StreamReadConstraints.DEFAULT_MAX_STRING_LEN) + "}", "json");
        assertThatThrownBy(() -> read(json)).isInstanceOf(HttpMessageNotReadableException.class)
            .hasMessageContaining("String value length");
    }
}
