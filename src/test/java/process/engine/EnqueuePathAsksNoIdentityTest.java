package process.engine;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.directory.WorkspaceDirectory;
import process.model.service.impl.TransactionServiceImpl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workspace pause (owner decision 2026-09-24) is decided on the enqueue path from Core's local view,
 * workspace_directory, which Identity's events keep -- never by a synchronous call to Identity: the enqueuer
 * claims slots FOR UPDATE SKIP LOCKED, one short transaction each, and an HTTP call inside that lock would make
 * every replica's scheduling as slow and as available as Identity.
 *
 * Structural, so it cannot drift silently: nothing the enqueuer holds -- the engine, its BulkAction, its store,
 * the directory it asks -- is able to reach Identity or make an HTTP call at all.
 */
class EnqueuePathAsksNoIdentityTest {

    private static final String[] FORBIDDEN = { "process.identity.", "RestTemplate", "WebClient", "HttpClient", "Feign" };

    @Test
    void theWorkspaceDirectoryIsBuiltFromTheDatabaseAlone() {
        for (Constructor<?> constructor : WorkspaceDirectory.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes()).as(constructor.toString()).containsOnly(JdbcTemplate.class);
        }
    }

    @Test
    void nothingTheEnqueuerHoldsCanCallIdentity() {
        for (Class<?> holder : new Class<?>[] { ProducerBulkEngine.class, BulkAction.class, TransactionServiceImpl.class,
            WorkspaceDirectory.class }) {
            for (Field field : holder.getDeclaredFields()) {
                String type = field.getType().getName();
                assertThat(Arrays.stream(FORBIDDEN).noneMatch(type::contains))
                    .as("%s.%s is a %s", holder.getSimpleName(), field.getName(), type).isTrue();
            }
        }
    }
}
