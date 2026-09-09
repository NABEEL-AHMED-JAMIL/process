package process.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Whether AnalyticsEngine is a seam somebody could actually use, or one that exists on paper.
 *
 * Document 05 asks for an engine abstraction and the interface's own javadoc calls itself "a
 * seam, not a promise that a second engine is a drop-in". Two things stopped it being usable at
 * all, and both are pinned here.
 *
 * @author Nabeel Ahmed
 */
class AnalyticsEngineSeamTest {

    @Test
    void theDuckDbBeanCanBeTurnedOff_soASecondEngineCanExist() {
        // Unconditional, a deployment supplying its own engine gets NoUniqueBeanDefinitionException
        // and the context refuses to start. The property is asserted rather than the behaviour
        // because the behaviour is Spring's, and what this module owns is the switch.
        ConditionalOnProperty condition =
            DuckDbAnalyticsEngine.class.getAnnotation(ConditionalOnProperty.class);

        assertNotNull(condition, "the default engine must be switchable off");
        assertEquals("analytics.engine", condition.name()[0]);
        assertEquals("duckdb", condition.havingValue());
        assertTrue(condition.matchIfMissing(),
            "a deployment that has never heard of this property must still get an engine");
    }

    @Test
    void shutdownReachesAnEngineThatIsNotDuckDb() {
        // This used to be `instanceof DuckDbAnalyticsEngine`, so the seam covered every method
        // except the one that stops the background thread: a second engine would have been
        // constructed, used, and quietly never shut down. A mock rather than a hand-written stub
        // because the interface has eight methods and seven of them are noise to this question.
        AnalyticsEngine other = mock(AnalyticsEngine.class);

        new AnalyticsQueryService(other, new RunningQueries()).shutdown();

        verify(other, times(1)).shutdown();
    }
}
