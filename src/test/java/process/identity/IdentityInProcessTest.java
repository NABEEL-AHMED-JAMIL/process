package process.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-107: with identity.mode=remote no Identity bean is made in process -- nothing signs a token, writes
 * the six tables or answers /auth.json -- and HttpIdentity answers the port. With the property unset or
 * local, everything is as it was and HttpIdentity is not made.
 */
class IdentityInProcessTest {

    @IdentityInProcess
    @Component
    static class InProcessOnly {
    }

    private static Class<?> classOf(String path) throws ClassNotFoundException {
        return Class.forName("process." + path.replace(".java", "").replace('/', '.'));
    }

    @Test
    void everyIdentityBeanExistsOnlyWhileIdentityRunsHere() throws Exception {
        List<String> unmarked = new ArrayList<>();
        for (String path : IdentityPortBoundaryTest.IDENTITY) {
            if (!path.endsWith(".java")) {
                continue;
            }
            Class<?> type = classOf(path);
            boolean bean = type.isAnnotationPresent(Component.class) || type.isAnnotationPresent(Service.class)
                || type.isAnnotationPresent(RestController.class) || type.isAnnotationPresent(Controller.class)
                || type.isAnnotationPresent(Configuration.class);
            if (bean && !type.isAnnotationPresent(IdentityInProcess.class)) {
                unmarked.add(path);
            }
        }
        assertThat(unmarked).as("Identity beans without @IdentityInProcess").isEmpty();
        // The signing package is listed as a directory.
        for (String signing : new String[] {"SigningKeys", "SigningKeyWarmup", "JdbcSigningKeyStore"}) {
            assertThat(Class.forName("process.security.signing." + signing).isAnnotationPresent(IdentityInProcess.class)).as(signing).isTrue();
        }
    }

    @Test
    void httpIdentityIsMadeOnlyInRemoteMode() {
        ConditionalOnProperty remote = HttpIdentity.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(remote.name()).containsExactly("identity.mode");
        assertThat(remote.havingValue()).isEqualTo("remote");
        assertThat(remote.matchIfMissing()).isFalse();
    }

    @Test
    void theMarkerKeepsABeanInLocalModeAndByDefaultAndDropsItInRemoteMode() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(InProcessOnly.class);

        runner.run(context -> assertThat(context).hasSingleBean(InProcessOnly.class));
        runner.withPropertyValues("identity.mode=local").run(context -> assertThat(context).hasSingleBean(InProcessOnly.class));
        runner.withPropertyValues("identity.mode=remote").run(context -> assertThat(context).doesNotHaveBean(InProcessOnly.class));
    }
}
