package process.security.signing;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.barco.platform.error.PlatformException;
import org.barco.platform.security.CallerIdentity;
import org.barco.platform.security.Jwks;
import org.barco.platform.security.JwksKeys;
import org.barco.platform.security.JwtVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.util.EncryptionUtil;
import process.util.JwtUtil;

import javax.crypto.SecretKey;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-92 (P11): Identity makes and keeps its RS256 keys, publishes only their public halves, and after
 * the switch no service but Identity can mint a token another will accept.
 */
class SigningKeysTest {

    /** identity_signing_key as the database keeps it: at most one active row. */
    static final class MemoryStore implements SigningKeyStore {
        final List<StoredSigningKey> rows = new ArrayList<>();
        final AtomicInteger publishedReads = new AtomicInteger();

        @Override
        public synchronized Optional<StoredSigningKey> active() {
            return this.rows.stream().filter(r -> StoredSigningKey.ACTIVE.equals(r.getStatus())).findFirst();
        }

        @Override
        public synchronized List<StoredSigningKey> published() {
            this.publishedReads.incrementAndGet();
            return new ArrayList<>(this.rows);
        }

        @Override
        public synchronized boolean insertActiveIfNone(StoredSigningKey key) {
            if (this.active().isPresent()) {
                return false;
            }
            this.rows.add(key);
            return true;
        }

        @Override
        public synchronized void retire(String kid) {
            for (int i = 0; i < this.rows.size(); i++) {
                StoredSigningKey row = this.rows.get(i);
                if (row.getKid().equals(kid)) {
                    this.rows.set(i, new StoredSigningKey(row.getKid(), row.getPublicKey(), row.getSealedPrivateKey(),
                        StoredSigningKey.RETIRED, row.getCreatedAt(), new Timestamp(System.currentTimeMillis())));
                }
            }
        }
    }

    private final MemoryStore store = new MemoryStore();
    private EncryptionUtil seal;
    private final SecretKeyHolder shared = new SecretKeyHolder();

    static final class SecretKeyHolder {
        final SecretKey key = Keys.secretKeyFor(SignatureAlgorithm.HS256);
        String base64() {
            return Base64.getEncoder().encodeToString(this.key.getEncoded());
        }
    }

    @BeforeEach
    void setUp() {
        this.seal = new EncryptionUtil();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 3 + 7);
        ReflectionTestUtils.setField(this.seal, "currentKeyId", "t1");
        ReflectionTestUtils.setField(this.seal, "currentKey", Base64.getEncoder().encodeToString(key));
    }

    private JwtUtil jwtUtil(SigningKeys keys, String algorithm, boolean acceptHs256) {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "base64Key", this.shared.base64());
        ReflectionTestUtils.setField(util, "accessTokenExpiryMinutes", 30L);
        ReflectionTestUtils.setField(util, "refreshTokenExpiryDays", 7L);
        ReflectionTestUtils.setField(util, "signingAlgorithm", algorithm);
        ReflectionTestUtils.setField(util, "acceptHs256", acceptHs256);
        ReflectionTestUtils.setField(util, "signingKeys", keys);
        return util;
    }

    private static String header(String token) {
        return new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))), StandardCharsets.UTF_8);
    }

    private static AppUser olivia() {
        AppUser user = new AppUser();
        user.setAppUserId(44L);
        user.setTenantId(1001L);
        user.setUsername("olivia@a.example");
        user.setUserRole(UserRole.TENANT_USER);
        return user;
    }

    /** Another service: platform-commons' verifier over the JWKS as /internal/jwks serves it. */
    private static JwtVerifier anotherService(SigningKeys identity) {
        return JwtVerifier.builder().rs256(new JwksKeys(URI.create("http://process/internal/jwks"),
            uri -> identity.jwks(), Duration.ofSeconds(30), System::currentTimeMillis)).build();
    }

    @Test
    void theFirstStartMakesOneKeySealedAndEveryInstanceSharesIt() {
        SigningKeys first = new SigningKeys(this.store, this.seal);
        String kid = first.active().getKid();

        assertThat(this.store.rows).hasSize(1);
        StoredSigningKey row = this.store.rows.get(0);
        assertThat(row.getSealedPrivateKey()).startsWith("kt1:")
            .doesNotContain(Base64.getEncoder().encodeToString(first.active().getPrivateKey().getEncoded()).substring(0, 40));
        assertThat(kid).isEqualTo(SigningKeys.thumbprint((RSAPublicKey) first.forKid(kid)));

        SigningKeys second = new SigningKeys(this.store, this.seal);
        assertThat(second.active().getKid()).as("a second instance uses the stored key").isEqualTo(kid);
        assertThat(this.store.rows).hasSize(1);
    }

    @Test
    void instancesStartingTogetherAgreeOnOneKey() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> kids = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                SigningKeys instance = new SigningKeys(this.store, this.seal);
                kids.add(pool.submit(() -> {
                    start.await();
                    return instance.active().getKid();
                }));
            }
            start.countDown();
            Set<String> distinct = ConcurrentHashMap.newKeySet();
            for (Future<String> kid : kids) distinct.add(kid.get(30, TimeUnit.SECONDS));
            assertThat(distinct).hasSize(1);
            assertThat(this.store.active()).isPresent();
            assertThat(this.store.rows.stream().filter(r -> StoredSigningKey.ACTIVE.equals(r.getStatus())).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theJwksCarriesPublicHalvesOnly() {
        SigningKeys keys = new SigningKeys(this.store, this.seal);
        String kid = keys.active().getKid();

        String jwks = keys.jwks();

        assertThat(Jwks.parse(jwks)).containsOnlyKeys(kid);
        assertThat(jwks).doesNotContain("\"d\"").doesNotContain("\"p\"").doesNotContain("\"q\"")
            .doesNotContain(this.store.rows.get(0).getSealedPrivateKey());
    }

    @Test
    void anRs256TokenFromIdentityVerifiesInAnotherServiceAndInProcess() {
        SigningKeys identity = new SigningKeys(this.store, this.seal);
        JwtUtil jwt = this.jwtUtil(identity, "RS256", false);

        String token = jwt.generateAccessToken(olivia());

        assertThat(header(token)).contains("\"alg\":\"RS256\"").contains("\"kid\":\"" + identity.active().getKid() + "\"");
        CallerIdentity caller = anotherService(identity).verify(token);
        assertThat(caller.getAppUserId()).isEqualTo(44L);
        assertThat(caller.getTenantId()).isEqualTo(1001L);
        Claims claims = jwt.parseClaims(token);
        assertThat(claims.getSubject()).isEqualTo("olivia@a.example");
    }

    /**
     * The point of P11. A service holds Identity's public keys and -- until the owner removes it -- the
     * shared secret. Once jwt.accept-hs256 is off, nothing it can make is accepted.
     */
    @Test
    void afterTheSwitchNoServiceButIdentityCanMintAToken() {
        SigningKeys identity = new SigningKeys(this.store, this.seal);
        JwtUtil process = this.jwtUtil(identity, "RS256", false);
        String kid = identity.active().getKid();
        Date now = new Date();
        KeyPair own = Keys.keyPairFor(SignatureAlgorithm.RS256);

        String withTheSharedSecret = Jwts.builder().setSubject("root@example.com").claim("appUserId", 1)
            .claim("userRole", "PLATFORM_ADMIN").claim("type", "access").setExpiration(new Date(now.getTime() + 60_000))
            .signWith(this.shared.key, SignatureAlgorithm.HS256).compact();
        String withItsOwnKeyAndIdentitysKid = Jwts.builder().setHeaderParam("kid", kid).setSubject("root@example.com")
            .claim("appUserId", 1).claim("userRole", "PLATFORM_ADMIN").claim("type", "access")
            .setExpiration(new Date(now.getTime() + 60_000)).signWith(own.getPrivate(), SignatureAlgorithm.RS256).compact();
        String withThePublicKeyAsASecret = Jwts.builder().setHeaderParam("kid", kid).setSubject("root@example.com")
            .claim("appUserId", 1).claim("userRole", "PLATFORM_ADMIN").claim("type", "access")
            .setExpiration(new Date(now.getTime() + 60_000))
            .signWith(Keys.hmacShaKeyFor(identity.forKid(kid).getEncoded()), SignatureAlgorithm.HS256).compact();

        for (String forged : new String[] {withTheSharedSecret, withItsOwnKeyAndIdentitysKid, withThePublicKeyAsASecret}) {
            assertThatThrownBy(() -> process.parseClaims(forged)).isInstanceOf(JwtException.class);
            assertThatThrownBy(() -> anotherService(identity).verify(forged)).isInstanceOf(PlatformException.class);
        }
    }

    /** The transition: HS256 is still minted by default and still read, so nothing breaks before every service moves. */
    @Test
    void duringTheTransitionHs256IsMintedAndBothAreRead() {
        SigningKeys identity = new SigningKeys(this.store, this.seal);
        JwtUtil hs256 = this.jwtUtil(identity, "HS256", true);
        JwtUtil rs256 = this.jwtUtil(identity, "RS256", true);

        String old = hs256.generateAccessToken(olivia());
        String next = rs256.generateAccessToken(olivia());

        assertThat(JwtVerifier.fromBase64Secret(this.shared.base64()).verify(old).getAppUserId())
            .as("a 1.6.0 service still verifies what process mints by default").isEqualTo(44L);
        assertThat(hs256.parseClaims(next).getSubject()).isEqualTo("olivia@a.example");
        assertThat(rs256.parseClaims(old).getSubject()).isEqualTo("olivia@a.example");
    }

    @Test
    void rotatingKeepsTheOldKeyPublishedSoNobodyIsSignedOut() {
        SigningKeys identity = new SigningKeys(this.store, this.seal);
        JwtUtil jwt = this.jwtUtil(identity, "RS256", false);
        String before = jwt.generateAccessToken(olivia());
        String oldKid = identity.active().getKid();

        String newKid = identity.rotate();

        assertThat(newKid).isNotEqualTo(oldKid);
        assertThat(Jwks.parse(identity.jwks())).containsOnlyKeys(oldKid, newKid);
        assertThat(anotherService(identity).verify(before).getAppUserId()).isEqualTo(44L);
        String after = jwt.generateAccessToken(olivia());
        assertThat(header(after)).contains("\"kid\":\"" + newKid + "\"");
    }

    /** A token naming a kid nobody has reloads the keys at most once per floor, so made-up kids cannot drive queries. */
    @Test
    void madeUpKidsCannotDriveDatabaseReads() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SigningKeys identity = new SigningKeys(this.store, this.seal, now::get);
        identity.active();
        int reads = this.store.publishedReads.get();

        for (int i = 0; i < 20; i++) {
            assertThat(identity.forKid("made-up-" + i)).isNull();
        }
        assertThat(this.store.publishedReads.get() - reads).isZero();
        now.addAndGet(SigningKeys.RELOAD_FLOOR_MILLIS);
        assertThat(identity.forKid("made-up")).isNull();
        assertThat(this.store.publishedReads.get() - reads).isEqualTo(1);
    }

    @Test
    void aKeySealedUnderAnotherKeyStopsStartupRatherThanSigningWithNothing() {
        new SigningKeys(this.store, this.seal).active();
        EncryptionUtil other = new EncryptionUtil();
        ReflectionTestUtils.setField(other, "currentKeyId", "t2");
        ReflectionTestUtils.setField(other, "currentKey", Base64.getEncoder().encodeToString(new byte[32]));

        assertThatThrownBy(() -> new SigningKeys(this.store, other).active()).isInstanceOf(IllegalStateException.class);
        assertThat(this.store.rows.stream().map(StoredSigningKey::getKid).collect(Collectors.toSet())).hasSize(1);
    }
}
