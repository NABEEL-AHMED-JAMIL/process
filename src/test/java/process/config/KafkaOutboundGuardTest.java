package process.config;

import org.barco.platform.net.OutboundAddresses;
import org.junit.jupiter.api.Test;
import process.model.pojo.KafkaConnectionProfile;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S9 (security review 2026-10-05): a workspace's own Kafka profile may not name the platform's network -- not when it
 * is saved, and not when a client is built for a row saved before the rule. The platform's own profiles may.
 */
class KafkaOutboundGuardTest {

    private static final OutboundAddresses.Resolver DNS = host -> {
        switch (host) {
            case "kafka":
            case "identity_service":
                return new InetAddress[] { InetAddress.getByAddress(host, new byte[] { (byte) 172, 18, 0, 9 }) };
            case "broker.example.com":
                return new InetAddress[] { InetAddress.getByAddress(host, new byte[] { 93, (byte) 184, (byte) 216, 34 }) };
            default:
                if (host.matches("[0-9.]+") || host.contains(":")) {
                    return InetAddress.getAllByName(host);
                }
                throw new UnknownHostException(host);
        }
    };

    private static KafkaTemplateProvider provider(String... allowedHosts) {
        KafkaTemplateProvider provider = new KafkaTemplateProvider(null, null);
        provider.useOutbound(new OutboundAddresses(Arrays.asList(allowedHosts), DNS));
        return provider;
    }

    private static KafkaConnectionProfile profile(Long tenantId, String bootstrap) {
        KafkaConnectionProfile p = new KafkaConnectionProfile();
        p.setTenantId(tenantId);
        p.setProfileName("orders");
        p.setBootstrapServers(bootstrap);
        p.setSecurityProtocol("PLAINTEXT");
        return p;
    }

    @Test
    void privateAndMetadataBrokersAreRefused() {
        KafkaTemplateProvider provider = provider();

        assertThat(provider.outboundRefusal("identity_service:9100")).isNotNull();
        assertThat(provider.outboundRefusal("broker.example.com:9092,10.0.0.8:9092")).isNotNull();
        assertThat(provider.outboundRefusal("169.254.169.254:80")).isNotNull();
        assertThat(provider.outboundRefusal("broker.example.com:9092")).isNull();
    }

    @Test
    void anAllowListedBrokerIsUsable() {
        assertThat(provider("kafka").outboundRefusal("kafka:9092")).isNull();
    }

    @Test
    void aWorkspaceRowSavedBeforeTheGuardGetsNoClient() {
        assertThatThrownBy(() -> provider().commonClientProps(profile(2924L, "kafka:9092")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePlatformsOwnProfileIsNotJudged() {
        assertThat(provider().commonClientProps(profile(null, "kafka:9092"))).containsKey("bootstrap.servers");
    }
}
