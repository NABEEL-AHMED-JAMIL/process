package process.ai;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.ResponseDto;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoint a model connection names arrives from the browser and the provider's answer
 * is handed back, so an unchecked one is a way to read whatever the server can reach. These
 * pin the refusals; the allow-list is the one opt-in.
 */
public class AiEndpointPolicyTest {

    private AiEndpointPolicy policyAllowing(String hosts) {
        AiEndpointPolicy policy = new AiEndpointPolicy();
        ReflectionTestUtils.setField(policy, "allowedEndpointHosts", hosts);
        return policy;
    }

    private static void refused(ResponseDto answer) {
        assertThat(answer).isNotNull();
        assertThat(answer.getStatus()).isEqualTo("ERROR");
    }

    @Test void aPrivateAddressIsNotAnAiProvider() { refused(this.policyAllowing("host.docker.internal").validateEndpoint("https://10.0.0.5/v1/chat/completions")); }
    @Test void loopbackIsNotAnAiProviderEither() { refused(this.policyAllowing("host.docker.internal").validateEndpoint("https://127.0.0.1/v1")); }
    @Test void theCloudMetadataAddressIsRefused() { refused(this.policyAllowing("host.docker.internal").validateEndpoint("https://169.254.169.254/latest/meta-data")); }
    @Test void theOtherPrivateRangesAreRefusedToo() {
        refused(this.policyAllowing("").validateEndpoint("https://192.168.1.10/v1"));
        refused(this.policyAllowing("").validateEndpoint("https://172.16.0.1/v1"));
        refused(this.policyAllowing("").validateEndpoint("https://100.64.0.1/v1"));
    }
    @Test void plainHttpIsRefusedForAHostNobodyAllowListed() { refused(this.policyAllowing("host.docker.internal").validateEndpoint("http://example.com/v1")); }
    @Test void somethingThatIsNotAUrlIsRefusedBeforeAnythingIsDialled() { refused(this.policyAllowing("").validateEndpoint("not a url at all")); }
    @Test void theHostTheOperatorAllowListedStaysCallableOverPlainHttp() {
        assertThat(this.policyAllowing("host.docker.internal, 127.0.0.1").validateEndpoint("http://127.0.0.1:11434/api/chat")).isNull();
    }
    @Test void anAllowListedNameDoesNotCoverEverythingUnderIt() { refused(this.policyAllowing("internal.example").validateEndpoint("http://evil.internal.example/v1")); }
}
