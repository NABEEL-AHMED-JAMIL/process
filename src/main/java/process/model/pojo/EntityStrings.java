package process.model.pojo;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import process.util.LocalDateTimeAdapter;

import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * The toString() of the entities that had a Gson one (MIG-261): the entity's own columns as JSON, and nothing it
 * points to.
 *
 * The plain Gson each of them used walked every field. On an entity read outside a transaction -- the normal case
 * now that open-in-view is off -- that meant an uninitialised Hibernate proxy (Gson reached its Class and threw) or a
 * lazy collection whose session had closed (a LazyInitializationException); and on Java 17 any java.time field threw
 * too, the module system refusing the reflection. Eight of the eleven threw on a detached row, so a log line or an
 * exception message naming one would have failed with it.
 *
 * Associations are left out rather than followed: each has its id column beside it (tenantId, jobId, jobQueueId,
 * kafkaConnectionProfileId...) where one is mapped, and following one is a query, or a failure, in a toString().
 * java.time values are written ISO-8601, as JobQueue's already were.
 */
final class EntityStrings {

    private static final Gson GSON = new GsonBuilder()
        .setExclusionStrategies(new ExclusionStrategy() {
            @Override
            public boolean shouldSkipField(FieldAttributes field) {
                return field.getAnnotation(ManyToOne.class) != null || field.getAnnotation(OneToMany.class) != null
                    || field.getAnnotation(OneToOne.class) != null || field.getAnnotation(ManyToMany.class) != null;
            }

            @Override
            public boolean shouldSkipClass(Class<?> type) {
                return false;
            }
        })
        .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
        .registerTypeAdapter(LocalDate.class, (JsonSerializer<LocalDate>) (value, type, context) -> new JsonPrimitive(value.toString()))
        .registerTypeAdapter(LocalTime.class, (JsonSerializer<LocalTime>) (value, type, context) -> new JsonPrimitive(value.toString()))
        .create();

    private EntityStrings() {}

    static String of(Object entity) {
        return GSON.toJson(entity);
    }
}
