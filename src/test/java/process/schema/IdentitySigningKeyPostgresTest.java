package process.schema;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import process.security.signing.JdbcSigningKeyStore;
import process.security.signing.SigningKeys;
import process.util.EncryptionUtil;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-92 against a real Postgres: V68's identity_signing_key, and instances starting together over an
 * empty table storing exactly one active key between them. Opt-in (IdentityPostgres).
 */
class IdentitySigningKeyPostgresTest {

    private static EncryptionUtil seal() {
        EncryptionUtil seal = new EncryptionUtil();
        ReflectionTestUtils.setField(seal, "currentKeyId", "t1");
        ReflectionTestUtils.setField(seal, "currentKey", Base64.getEncoder().encodeToString(new byte[] {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}));
        return seal;
    }

    @Test
    void instancesStartingTogetherStoreOneActiveKeyAndTheTableRefusesASecond() throws Exception {
        try (IdentityPostgres db = IdentityPostgres.create("mig92_keys").migrate()) {
            JdbcTemplate sql = db.sql();
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> kids = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    SigningKeys instance = new SigningKeys(new JdbcSigningKeyStore(sql, 7), seal());
                    kids.add(pool.submit(() -> {
                        start.await();
                        return instance.active().getKid();
                    }));
                }
                start.countDown();
                Set<String> distinct = new HashSet<>();
                for (Future<String> kid : kids) distinct.add(kid.get(60, TimeUnit.SECONDS));
                assertThat(distinct).hasSize(1);
            } finally {
                pool.shutdownNow();
            }
            assertThat(sql.queryForObject("SELECT count(*) FROM identity_signing_key WHERE status = 'active'", Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT private_key_sealed LIKE 'kt1:%' FROM identity_signing_key", Boolean.class)).isTrue();
            assertThatThrownBy(() -> sql.update("INSERT INTO identity_signing_key (kid, public_key, status) VALUES ('x', 'x', 'active')"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ux_identity_signing_key_one_active");
            assertThatThrownBy(() -> sql.update("INSERT INTO identity_signing_key (kid, public_key, status, algorithm) VALUES ('y', 'x', 'retired', 'HS256')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void aRetiredKeyStaysPublishedForTheLongestTokenLifeAndThenGoes() throws Exception {
        try (IdentityPostgres db = IdentityPostgres.create("mig92_rotate").migrate()) {
            JdbcTemplate sql = db.sql();
            SigningKeys keys = new SigningKeys(new JdbcSigningKeyStore(sql, 7), seal());
            String first = keys.active().getKid();
            String second = keys.rotate();

            assertThat(new JdbcSigningKeyStore(sql, 7).published()).extracting(k -> k.getKid()).containsExactly(second, first);

            sql.update("UPDATE identity_signing_key SET retired_at = now() - interval '9 days' WHERE kid = ?", first);
            assertThat(new JdbcSigningKeyStore(sql, 7).published()).extracting(k -> k.getKid()).containsExactly(second);
        }
    }
}
