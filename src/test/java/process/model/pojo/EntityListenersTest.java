package process.model.pojo;

import org.junit.jupiter.api.Test;

import javax.persistence.Entity;
import javax.persistence.EntityListeners;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every JPA entity listener has one constructor, taking nothing. Hibernate builds listeners through
 * Spring on the thread bootstrapping the EntityManagerFactory; a constructor with a dependency makes
 * Spring autowire it there, waiting on the singleton lock the main thread holds while it waits for
 * that same factory. Process then never finishes starting -- and no test here starts the application,
 * so this is the only place the deadlock can be caught before a deploy (it was not, once: MIG-53).
 */
class EntityListenersTest {

    @Test
    void everyEntityListenerHasOnlyANoArgumentConstructor() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Class<?> entity : entities()) {
            EntityListeners listeners = entity.getAnnotation(EntityListeners.class);
            if (listeners == null) {
                continue;
            }
            for (Class<?> listener : listeners.value()) {
                Constructor<?>[] constructors = listener.getDeclaredConstructors();
                if (constructors.length != 1 || constructors[0].getParameterCount() != 0) {
                    offenders.add(entity.getSimpleName() + " -> " + listener.getSimpleName());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    private static List<Class<?>> entities() throws IOException, ClassNotFoundException {
        List<Class<?>> found = new ArrayList<>();
        Path root = Paths.get("src", "main", "java");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList());
        }
        for (Path file : files) {
            if (!new String(Files.readAllBytes(file)).contains("@Entity")) {
                continue;
            }
            String name = root.relativize(file).toString().replace('/', '.').replace('\\', '.').replaceAll("\\.java$", "");
            Class<?> type = Class.forName(name);
            if (type.isAnnotationPresent(Entity.class)) {
                found.add(type);
            }
        }
        return found;
    }
}
