package process.model.service;

import java.util.List;

/**
 * Turns text into vectors for the RAG pipeline's chunk indexing and question retrieval.
 *
 * @author Nabeel Ahmed
 * */
public interface EmbeddingService {

    /** Whether an embedding model is actually reachable right now -- see EmbeddingServiceImpl. */
    boolean isAvailable();

    /** The vector dimension this instance's model produces, for building the OpenSearch mapping. */
    int dimensions();

    /**
     * The model name in use (e.g. "nomic-embed-text") -- recorded on every indexed chunk so a
     * later change of {@code embedding.model} doesn't silently mix vectors from two different
     * models in the same similarity search. Cosine similarity between vectors from different
     * models is meaningless; nothing enforces that today, this is what would let it be detected.
     */
    String model();

    float[] embed(String text) throws Exception;

    /**
     * One batched call to the embedding model for every element of {@code texts}, not one call
     * per element -- see EmbeddingServiceImpl for why a single request is safe here despite
     * indexing sometimes meaning hundreds of chunks.
     */
    List<float[]> embedAll(List<String> texts) throws Exception;
}
