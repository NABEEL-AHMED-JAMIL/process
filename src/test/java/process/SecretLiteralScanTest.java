package process;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-5: a secret written into a file that is committed is a secret given away -- process's AES key
 * and JWT key were, as docker-compose fallbacks. This scans what ships (the compose files, main
 * resources, db-init) and fails the build on a secret-named setting with a literal value, a
 * non-empty default on one, or a key-length base64 blob. Secrets come from the environment only.
 *
 * LocalStack's fixed "test" credentials are its convention, not a secret, and are the one value let by.
 */
class SecretLiteralScanTest {

    private static final String SECRET_NAME = "(?i)[A-Za-z0-9_.-]*(password|passwd|secret|token|(api|secret|encryption|private|service|access)[_.-]?key)";

    /** compose: NAME: literal, or NAME: ${VAR:-literal}. */
    private static final Pattern COMPOSE = Pattern.compile("^\\s*(?<name>" + SECRET_NAME + ")\\s*:\\s*(?<value>.+?)\\s*$");

    /** properties: name=literal, or name=${VAR:literal}. */
    private static final Pattern PROPERTY = Pattern.compile("^\\s*(?<name>" + SECRET_NAME + ")\\s*[=:]\\s*(?<value>.*?)\\s*$");

    private static final Pattern DEFAULTED = Pattern.compile("\\$\\{[A-Za-z0-9_.-]+:-?([^}]*)}");

    private static final Pattern KEY_BLOB = Pattern.compile("[A-Za-z0-9+/]{40,}={1,2}");

    private static final List<String> ALLOWED = Arrays.asList("test");

    @Test
    void nothingThatShipsCarriesASecret() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : this.shipped()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            boolean compose = file.getFileName().toString().startsWith("docker-compose");
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.trim().startsWith("#") || line.trim().startsWith("--")) {
                    continue;
                }
                String where = file + ":" + (i + 1);
                if (KEY_BLOB.matcher(line).find()) {
                    found.add(where + " holds a key-length base64 value");
                    continue;
                }
                boolean sql = file.toString().endsWith(".sql");
                Matcher setting = (compose ? COMPOSE : PROPERTY).matcher(line);
                if (sql || !setting.matches()) {
                    continue;
                }
                String value = setting.group("value").replaceAll("^[\"']|[\"']$", "");
                Matcher defaulted = DEFAULTED.matcher(value);
                String literal = defaulted.matches() ? defaulted.group(1) : (value.startsWith("${") ? "" : value);
                if (!literal.isEmpty() && !literal.startsWith("?") && !ALLOWED.contains(literal)) {
                    found.add(where + " gives " + setting.group("name") + " a literal value");
                }
            }
        }
        assertThat(found).as("secrets must come from the environment, never a committed file").isEmpty();
    }

    /** The scan must see what it guards, or an empty list would pass for a clean one. */
    @Test
    void theScanReachesTheFilesThatShip() throws IOException {
        List<String> names = this.shipped().stream().map(p -> p.getFileName().toString()).collect(Collectors.toList());
        assertThat(names).contains("docker-compose.yml", "application-dev.properties", "application-prod.properties");
    }

    @Test
    void itCatchesTheShapesThatLeakedBefore() {
        assertThat(PROPERTY.matcher("jwt.access-token.expiry-minutes=15").matches()).as("a setting about a token").isFalse();
        assertThat(PROPERTY.matcher("jwt.secret.key=abc").matches()).isTrue();
        assertThat(COMPOSE.matcher("      METER_SERVICE_KEY: ${METER_SERVICE_KEY:-abc}").matches()).isTrue();
        assertThat(COMPOSE.matcher("      JWT_SECRET_KEY: ${JWT_SECRET_KEY:-not-a-real-key-but-shaped-like-one}").matches()).isTrue();
        assertThat(DEFAULTED.matcher("${SPRING_DATASOURCE_PASSWORD:-admin}").matches()).isTrue();
        assertThat(PROPERTY.matcher("spring.datasource.password=admin").matches()).isTrue();
        assertThat(KEY_BLOB.matcher("x: RACT0Saglm5Lastgky0Z3RgMXiZbSjxiNvgbnQDtMwc=").find()).isTrue();
    }

    private List<Path> shipped() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> top = Files.list(Paths.get("."))) {
            top.filter(p -> p.getFileName().toString().matches("docker-compose.*\\.ya?ml")).forEach(files::add);
        }
        for (String root : new String[] {"src/main/resources", "db-init"}) {
            if (Files.isDirectory(Paths.get(root))) {
                try (Stream<Path> tree = Files.walk(Paths.get(root))) {
                    tree.filter(p -> p.toString().matches(".*\\.(properties|ya?ml|sql)$")).forEach(files::add);
                }
            }
        }
        return files;
    }
}
