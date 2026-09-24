package process.directory;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-153 / MIG-166: the identity topics are COMPACTED -- each event carries a whole state keyed by id, so the
 * broker keeps the latest per workspace and per person for ever, and a new consumer bootstraps by reading from
 * the beginning. A delete-policy topic would lose the directory after its retention.
 */
class IdentityTopicsConfigTest {

    private final IdentityTopicsConfig config = new IdentityTopicsConfig((short) 1);

    @Test
    void bothTopicsAreCompactedAndNamedAsIdentityWritesThem() {
        NewTopic users = this.config.identityUserTopic();
        NewTopic tenants = this.config.identityTenantTopic();
        assertThat(users.name()).isEqualTo("platform.identity.user.v1");
        assertThat(tenants.name()).isEqualTo("platform.identity.tenant.v1");
        assertThat(users.configs()).containsEntry("cleanup.policy", "compact");
        assertThat(tenants.configs()).containsEntry("cleanup.policy", "compact");
    }
}
