package process.ai;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The model a run's step asks for: the run's "Run with...", else the schedule's, else nothing (the step's default). */
class ModelProfilesTest {

    @Test
    void theRunsChoiceWinsThenTheSchedulesThenTheDefault() {
        ModelProfiles profiles = ModelProfiles.of("{\"summary\":\"1204\",\"caption\":\"2100\"}", "{\"summary\":\"1300\"}");

        assertThat(profiles.forStep("summary").profile).isEqualTo("1300");
        assertThat(profiles.forStep("summary").source).isEqualTo(ModelProfiles.FROM_RUN);
        assertThat(profiles.forStep("caption").profile).isEqualTo("2100");
        assertThat(profiles.forStep("caption").source).isEqualTo(ModelProfiles.FROM_SCHEDULE);
        assertThat(profiles.forStep("tags").isDefault()).isTrue();
        assertThat(profiles.forStep("tags").source).isNull();
        assertThat(profiles.forStep(null).isDefault()).isTrue();
    }

    @Test
    void nothingStoredOrSomethingUnreadableAsksForNothing() {
        for (String stored : new String[] {null, "", "  ", "[1,2]", "not json", "{\"summary\": {\"x\": 1}}", "{\"summary\": \"abc\"}",
            "{\"summary\": \"0\"}", "{\"summary\": \"-5\"}"}) {
            assertThat(ModelProfiles.of(stored, stored).forStep("summary").isDefault()).as(String.valueOf(stored)).isTrue();
        }
    }

    @Test
    void aNumberIsReadAsTheSameId() {
        assertThat(ModelProfiles.of("{\"summary\": 1204}", null).forStep("summary").profile).isEqualTo("1204");
    }

    @Test
    void theSameChoiceIsAlwaysTheSameTextAndNoChoiceIsNull() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("summary", "1204");
        one.put("caption", "2100");
        Map<String, String> other = new LinkedHashMap<>();
        other.put("caption", "2100");
        other.put("summary", "1204");

        assertThat(ModelProfiles.write(one)).isEqualTo(ModelProfiles.write(other)).isEqualTo("{\"caption\":\"2100\",\"summary\":\"1204\"}");
        assertThat(ModelProfiles.write(new LinkedHashMap<>())).isNull();
        assertThat(ModelProfiles.read(ModelProfiles.write(one))).isEqualTo(one);
    }

    @Test
    void anOptionIdIsAPositiveNumberAsAiServiceParsesIt() {
        assertThat(ModelProfiles.isOptionId("1204")).isTrue();
        assertThat(ModelProfiles.isOptionId(" 1204 ")).isTrue();
        assertThat(ModelProfiles.isOptionId("0")).isFalse();
        assertThat(ModelProfiles.isOptionId("12a")).isFalse();
        assertThat(ModelProfiles.isOptionId("99999999999999999999")).as("past a long").isFalse();
        assertThat(ModelProfiles.isOptionId(null)).isFalse();
    }
}
