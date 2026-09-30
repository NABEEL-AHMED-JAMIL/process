package process.model.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-213 (SpotBugs RC_REF_COMPARISON): TagInfo ordered its payload ids by comparing the Long references,
 * so two equal ids outside the small-value cache (above 127) were never equal and sorted as "less".
 */
class ConfigurationMakerRequestTagInfoTest {

    private static ConfigurationMakerRequest.TagInfo tag(long payloadId) {
        ConfigurationMakerRequest.TagInfo tag = new ConfigurationMakerRequest.TagInfo("k", "p", "v");
        tag.setTaskPayloadId(Long.valueOf(payloadId));
        return tag;
    }

    @Test
    void equalPayloadIdsCompareEqualWhateverTheirSize() {
        assertThat(tag(5).compareTo(tag(5))).isZero();
        assertThat(tag(90_001).compareTo(tag(90_001))).isZero();
    }

    @Test
    void payloadIdsOrderByValue() {
        assertThat(tag(90_002).compareTo(tag(90_001))).isPositive();
        assertThat(tag(90_001).compareTo(tag(90_002))).isNegative();
    }
}
