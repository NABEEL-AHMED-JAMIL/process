package process;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every bean Spring makes can be made: a class with more than one constructor says which one Spring
 * uses, or has a no-argument one.
 *
 * No unit test starts the application context, so a bean Spring cannot construct passes the whole
 * suite and stops process at startup. It happened on 2026-09-24: SigningKeys gained a second,
 * test-only constructor, and process restarted seven times on "No default constructor found" before
 * it was rolled forward. This reads the same rule Spring does, at unit speed.
 */
class BeanConstructorsTest {

    @Test
    void aBeanWithSeveralConstructorsNamesTheOneSpringUses() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<String> ambiguous = new ArrayList<>();
        for (BeanDefinition bean : scan.findCandidateComponents("process")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length < 2) {
                continue;
            }
            boolean named = Arrays.stream(constructors).anyMatch(c -> c.isAnnotationPresent(Autowired.class));
            boolean noArgs = Arrays.stream(constructors).anyMatch(c -> c.getParameterCount() == 0);
            if (!named && !noArgs) {
                ambiguous.add(type.getName());
            }
        }
        assertThat(ambiguous).as("mark the constructor Spring should use with @Autowired").isEmpty();
    }
}
