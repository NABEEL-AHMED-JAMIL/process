package process.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Risk register #6 (2026-10-12): Core's CORS policy lives in CorsConfig alone. 23 controllers had copied
 * {@code @CrossOrigin(origins = "*")} from the one beside them -- the two newest on the day they were written -- so this fails
 * the build when any class under src/main/java carries {@code @CrossOrigin} again, "*" or otherwise: a controller's own
 * annotation would answer every origin for its paths (or a second Access-Control-Allow-Origin beside CorsConfig's), and never
 * for the 401 a token refusal makes. A path that needs different CORS is a change to CorsConfig, with its test.
 */
class CorsPolicyGuardTest {

    private static final Pattern ANNOTATION = Pattern.compile("^\\s*@(org\\.springframework\\.web\\.bind\\.annotation\\.)?CrossOrigin\\b");
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+org\\.springframework\\.web\\.bind\\.annotation\\.CrossOrigin\\s*;");

    @Test
    void noControllerCarriesItsOwnCrossOrigin() throws IOException {
        Path main = Paths.get("src", "main", "java");
        assertThat(main).isDirectory();
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (ANNOTATION.matcher(lines.get(i)).find() || IMPORT.matcher(lines.get(i)).find()) {
                        found.add(main.relativize(file) + ":" + (i + 1) + "  " + lines.get(i).trim());
                    }
                }
            }
        }
        assertThat(found).as("CORS is CorsConfig's (process.cors.allowed-origins); remove these and change CorsConfig instead")
            .isEmpty();
    }

    @Test
    void securityStillAnswersCorsAheadOfAuthentication() throws IOException {
        String security = new String(Files.readAllBytes(Paths.get("src", "main", "java", "process", "config", "SecurityConfig.java")),
            StandardCharsets.UTF_8);
        assertThat(security).as("SecurityConfig's .cors() runs CorsConfig ahead of JwtAuthenticationFilter").contains(".cors()");
    }
}
