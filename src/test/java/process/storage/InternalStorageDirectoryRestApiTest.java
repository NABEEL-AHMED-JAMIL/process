package process.storage;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.repository.KafkaConnectionProfileRepository;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What Storage asks Core about its connections (MIG-68 B): which Kafka profiles name an alias, and
 * who a user id is. Internal-token only, since neither answer is scoped to a signed-in caller.
 */
class InternalStorageDirectoryRestApiTest {

    private static final String TOKEN = "internal-secret";

    private final KafkaConnectionProfileRepository profiles = mock(KafkaConnectionProfileRepository.class);
    private final UserNameResolver names = mock(UserNameResolver.class);
    private final InternalStorageDirectoryRestApi api = new InternalStorageDirectoryRestApi(this.profiles, this.names, TOKEN);

    private static KafkaConnectionProfile profile(String name, Long tenantId, String truststore, String keystore) {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        profile.setProfileName(name);
        profile.setTenantId(tenantId);
        profile.setSslTruststoreBucket(truststore);
        profile.setSslKeystoreBucket(keystore);
        return profile;
    }

    private static Map<String, Object> aliasBody(String alias) {
        return Collections.singletonMap("alias", alias);
    }

    @Test
    void everyLiveProfileNamingTheAliasInAnyWorkspaceIsListedWithItsWorkspace() {
        when(this.profiles.findVisibleToPlatformAdmin(Status.Delete)).thenReturn(Arrays.asList(
            profile("acme-events", 2901L, "kafka-certs", null),
            profile("globex-events", 2902L, null, "kafka-certs"),
            profile("platform-events", null, "kafka-certs", "kafka-certs"),
            profile("unrelated", 2901L, "other", "other")));

        ResponseEntity<?> answer = this.api.kafkaReferences(TOKEN, aliasBody("kafka-certs"));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs = (List<Map<String, Object>>) answer.getBody();
        assertThat(refs).extracting(r -> r.get("profileName")).containsExactly("acme-events", "globex-events", "platform-events");
        assertThat(refs).extracting(r -> r.get("tenantId")).containsExactly(2901L, 2902L, null);
        assertThat(refs).extracting(r -> r.get("alias")).containsOnly("kafka-certs");
    }

    @Test
    void namesComeBackByIdAndUnknownIdsAreLeftOut() {
        Map<Long, String> found = new HashMap<>();
        found.put(42L, "Ada Admin");
        when(this.names.namesFor(anyCollection())).thenReturn(found);

        ResponseEntity<?> answer = this.api.userNames(TOKEN, Collections.singletonMap("ids", Arrays.asList(42, 43)));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answer.getBody()).isEqualTo(Collections.singletonMap("42", "Ada Admin"));
    }

    @Test
    void withoutTheInternalTokenNothingIsRead() {
        assertThat(this.api.kafkaReferences(null, aliasBody("kafka-certs")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(this.api.kafkaReferences("wrong", aliasBody("kafka-certs")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(this.api.userNames("wrong", Collections.singletonMap("ids", Collections.singletonList(42))).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(this.profiles, this.names);
    }

    @Test
    void anUnconfiguredTokenRefusesEveryoneRatherThanMatchingABlankOne() {
        InternalStorageDirectoryRestApi unconfigured = new InternalStorageDirectoryRestApi(this.profiles, this.names, "");
        assertThat(unconfigured.kafkaReferences("", aliasBody("kafka-certs")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aMissingAliasIsABadRequest() {
        assertThat(this.api.kafkaReferences(TOKEN, Collections.emptyMap()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
