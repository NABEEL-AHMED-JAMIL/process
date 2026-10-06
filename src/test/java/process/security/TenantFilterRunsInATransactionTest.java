package process.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-261: every method that turns the tenant filter on runs in a transaction of its own.
 *
 * The filter is enabled on a Hibernate session, and the reads after it only see it if they run on that same
 * session. Outside a transaction the shared EntityManager has no session to unwrap -- each call gets a fresh one
 * -- so TenantFilterHelper refuses the read. While open-in-view was on, the request's session stood in for the
 * missing transaction and hid a method that had none; SourceTaskServiceImpl.fetchSourceTaskWithSourceTaskId was
 * one, its @Transactional stranded above a private method that had been inserted between the two. With
 * open-in-view off that endpoint answered 500. This keeps every caller public and transactional, so the proxy
 * opens the transaction the filter needs.
 */
class TenantFilterRunsInATransactionTest {

    private static final Path MAIN = Paths.get("src/main/java");
    private static final Pattern DECLARATION =
        Pattern.compile("^\\s*(public|protected|private)\\s[^=;]*?\\b(\\w+)\\s*\\([^;]*$");

    @Test
    void everyMethodThatEnablesTheTenantFilterIsPublicAndTransactional() throws Exception {
        List<String> callers = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (Path source : sources()) {
            String relative = MAIN.relativize(source).toString().replace('\\', '/');
            if (relative.equals("process/security/TenantFilterHelper.java")) {
                continue;
            }
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).contains(".enableIfNeeded(")) {
                    continue;
                }
                String method = enclosingMethod(lines, i);
                Class<?> type = Class.forName(relative.replace(".java", "").replace('/', '.'));
                String where = type.getSimpleName() + "." + method;
                callers.add(where);
                boolean found = false;
                for (Method candidate : type.getDeclaredMethods()) {
                    if (!candidate.getName().equals(method) || candidate.isSynthetic()) {
                        continue;
                    }
                    found = true;
                    boolean transactional = AnnotatedElementUtils.hasAnnotation(candidate, Transactional.class)
                        || AnnotatedElementUtils.hasAnnotation(type, Transactional.class);
                    if (!Modifier.isPublic(candidate.getModifiers()) || !transactional) {
                        refused.add(where);
                    }
                }
                assertThat(found).as(where).isTrue();
            }
        }
        assertThat(callers).as("the scan found the callers it guards").contains("SourceTaskServiceImpl.fetchSourceTaskWithSourceTaskId",
            "SourceJobServiceImpl.listSourceJob", "JobAssistantServiceImpl.ask");
        assertThat(refused).isEmpty();
    }

    private static String enclosingMethod(List<String> lines, int from) {
        for (int i = from; i >= 0; i--) {
            Matcher declaration = DECLARATION.matcher(lines.get(i));
            if (declaration.find()) {
                return declaration.group(2);
            }
        }
        throw new IllegalStateException("no method encloses line " + (from + 1));
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }
}
