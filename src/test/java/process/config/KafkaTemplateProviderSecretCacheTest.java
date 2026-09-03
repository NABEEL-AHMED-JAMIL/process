package process.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.ObjectContentDto;
import process.model.pojo.KafkaConnectionProfile;
import process.model.service.StorageBrowserService;
import process.util.EncryptionUtil;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Which downloaded store a profile is served, and when the copy goes away.
 *
 * The sibling security test proves an unsaved profile never reads another one's download, which the
 * random directory alone would satisfy. These are about the saved profiles, where the name is
 * derived rather than random: the profile id separates two profiles, the digest of bucket and key
 * separates one store from the next on the same profile, and invalidate is what makes a repointed
 * profile fetch again instead of opening the file left over from before.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class KafkaTemplateProviderSecretCacheTest {

    private static final long PROFILE_ID = 42L;
    private static final String BUCKET = "etl-bucket";
    private static final String OLD_STORE = "kafka-secrets/7/1a2b/2026-08-01/truststore.p12";
    private static final String NEW_STORE = "kafka-secrets/7/9z8y/2026-08-31/truststore.p12";

    @Mock
    private EncryptionUtil encryptionUtil;
    @Mock
    private StorageBrowserService storageBrowserService;

    private KafkaTemplateProvider provider;
    private final List<String> downloaded = new ArrayList<>();

    @BeforeEach
    void setUp() {
        this.provider = new KafkaTemplateProvider(this.encryptionUtil, null, null, this.storageBrowserService);
    }

    /** Every object answers with its own key as its content, so a served file names where it came from. */
    private void eachObjectAnswersWithItsOwnKey() {
        when(this.storageBrowserService.readForWorkflow(anyString(), anyString()))
            .thenAnswer(call -> {
                String key = call.getArgument(1);
                this.downloaded.add(call.getArgument(0) + "/" + key);
                return new ObjectContentDto(
                    new ByteArrayInputStream(("bytes-of-" + key).getBytes(StandardCharsets.UTF_8)),
                    "application/octet-stream", 1L, "truststore.p12");
            });
    }

    private KafkaConnectionProfile profile(Long profileId, String objectKey) {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        profile.setKafkaConnectionProfileId(profileId);
        profile.setProfileName("orders");
        profile.setBootstrapServers("broker:9093");
        profile.setSecurityProtocol("SSL");
        profile.setSslTruststoreBucket(BUCKET);
        profile.setSslTruststoreLocation(objectKey);
        return profile;
    }

    private String truststorePathFor(Long profileId, String objectKey) {
        return (String) this.provider.commonClientProps(this.profile(profileId, objectKey))
            .get("ssl.truststore.location");
    }

    @Test
    void theSameProfileAndObjectIsFetchedOnceAndReadBackAfterwards(@TempDir Path cacheDir) {
        ReflectionTestUtils.setField(this.provider, "secretCacheDir", cacheDir.toString());
        this.eachObjectAnswersWithItsOwnKey();

        String first = this.truststorePathFor(PROFILE_ID, OLD_STORE);
        String second = this.truststorePathFor(PROFILE_ID, OLD_STORE);

        assertThat(second).isEqualTo(first);
        assertThat(this.downloaded).containsExactly(BUCKET + "/" + OLD_STORE);
    }

    /**
     * A rotated certificate is a new key rather than a new copy at the old one, so the cached name
     * has to move with it -- otherwise the profile keeps opening the store it was pointed away from.
     */
    @Test
    void repointingAProfileAtAnotherStoreDoesNotServeTheFileFetchedForTheOldOne(@TempDir Path cacheDir)
        throws Exception {
        ReflectionTestUtils.setField(this.provider, "secretCacheDir", cacheDir.toString());
        this.eachObjectAnswersWithItsOwnKey();

        String first = this.truststorePathFor(PROFILE_ID, OLD_STORE);
        String second = this.truststorePathFor(PROFILE_ID, NEW_STORE);

        assertThat(second).isNotEqualTo(first);
        assertThat(new String(Files.readAllBytes(Paths.get(second)), StandardCharsets.UTF_8))
            .isEqualTo("bytes-of-" + NEW_STORE);
        assertThat(this.downloaded)
            .containsExactly(BUCKET + "/" + OLD_STORE, BUCKET + "/" + NEW_STORE);
    }

    /** Two profiles naming one object get a copy each, so neither is ever handed the other's file. */
    @Test
    void twoSavedProfilesNamingTheSameObjectDoNotShareACopy(@TempDir Path cacheDir) {
        ReflectionTestUtils.setField(this.provider, "secretCacheDir", cacheDir.toString());
        this.eachObjectAnswersWithItsOwnKey();

        Path first = Paths.get(this.truststorePathFor(PROFILE_ID, OLD_STORE));
        Path second = Paths.get(this.truststorePathFor(PROFILE_ID + 1L, OLD_STORE));

        assertThat(second).isNotEqualTo(first);
        assertThat(second.getParent()).isNotEqualTo(first.getParent());
        assertThat(this.downloaded).hasSize(2);
    }

    /**
     * What a profile edit relies on: the row is saved and the cache invalidated, and the next
     * connection must go back to storage rather than open whatever the previous store left behind.
     */
    @Test
    void invalidatingAProfileTakesItsCachedStoreOffDisk(@TempDir Path cacheDir) {
        ReflectionTestUtils.setField(this.provider, "secretCacheDir", cacheDir.toString());
        this.eachObjectAnswersWithItsOwnKey();
        Path cached = Paths.get(this.truststorePathFor(PROFILE_ID, OLD_STORE));
        assertThat(cached).exists();

        this.provider.invalidate(PROFILE_ID);

        assertThat(cached).doesNotExist();
        assertThat(this.truststorePathFor(PROFILE_ID, OLD_STORE)).isEqualTo(cached.toString());
        assertThat(this.downloaded).hasSize(2);
    }

}
