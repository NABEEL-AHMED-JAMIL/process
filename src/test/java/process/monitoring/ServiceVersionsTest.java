package process.monitoring;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner #21: Service health's version column is what scripts/deploy.sh recorded for the service; anything else in the hash
 * (a hand-written field, a shape it does not know) is no version rather than a wrong one. The error rate is 5xx against
 * requests, and none when nothing was asked.
 */
class ServiceVersionsTest {

    @Test
    void aDeploysRecordIsReadAndAnythingElseIsNoVersion() {
        ServiceVersions.Version v = ServiceVersions.parse("{\"version\":\"e7023e7\",\"branch\":\"media-extraction\",\"at\":1760900000}");
        assertThat(v.version).isEqualTo("e7023e7");
        assertThat(v.branch).isEqualTo("media-extraction");
        assertThat(v.deployedAt).isEqualTo(Instant.ofEpochSecond(1_760_900_000L));

        assertThat(ServiceVersions.parse("{\"version\":\"<script>\"}")).isNull();
        assertThat(ServiceVersions.parse("{\"branch\":\"main\"}")).isNull();
        assertThat(ServiceVersions.parse("not json")).isNull();
        assertThat(ServiceVersions.parse("{\"version\":\"abc1234\",\"branch\":\"has space\"}").branch).isNull();
    }

    @Test
    void theErrorRateIsFiveHundredsAgainstRequests() {
        assertThat(HealthRestApi.errorPct(new BigDecimal("200"), new BigDecimal("3"))).isEqualByComparingTo("1.5");
        assertThat(HealthRestApi.errorPct(BigDecimal.ZERO, BigDecimal.ZERO)).isNull();
        assertThat(HealthRestApi.errorPct(null, BigDecimal.ONE)).isNull();
    }
}
