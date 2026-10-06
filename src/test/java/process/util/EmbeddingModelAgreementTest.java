package process.util;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import process.model.service.impl.EmbeddingServiceImpl;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant I2 (MIG-156): the embedding model is named identically where chunks are written and where
 * they are looked up.
 *
 * EmbeddingServiceImpl records the model on every chunk it embeds; OpenSearchRagClient filters
 * retrieval on the model it reads from configuration -- on purpose not from EmbeddingService, to keep
 * process.util free of process.model.service. The hazard that buys, verbatim from OpenSearchRagClient:
 * "If this default and EmbeddingServiceImpl's default ever disagree, retrieval filters on a model name
 * that was never written to a single chunk, every file reads as 'not indexed', and FileChatServiceImpl
 * re-chunks and re-embeds the whole file on every message forever. They must be changed together."
 *
 * So the test compares the two declarations -- property key AND default -- to each other, and names
 * the property only to prove it is the one both read.
 */
class EmbeddingModelAgreementTest {

    private static String declaration(Class<?> owner, String field) throws Exception {
        Field declared = owner.getDeclaredField(field);
        Value value = declared.getAnnotation(Value.class);
        assertThat(value).as("%s.%s is injected with @Value", owner.getSimpleName(), field).isNotNull();
        return value.value();
    }

    @Test
    void theWriteSideAndTheReadSideDeclareTheSameModel() throws Exception {
        String written = declaration(EmbeddingServiceImpl.class, "model");
        String filtered = declaration(OpenSearchRagClient.class, "configuredEmbeddingModel");

        assertThat(filtered)
            .as("OpenSearchRagClient must read the embedding model exactly as EmbeddingServiceImpl declares it")
            .isEqualTo(written);
        assertThat(written).startsWith("${embedding.model:");
    }
}
