package process.pipeline;

import java.util.List;

/**
 * MIG-364: the documents a run was started for -- approved documents of the type its job's document trigger names, one
 * or a batch that waited for the job (document_approval names the run on each). The step engine asks once per run, and
 * only when a step wants them (read_documents with no type, {{document.<field>}}).
 */
public interface RunDocuments {

    /** One approved document: the extraction (what the review screen opens), its type and the type's version. */
    final class Document {
        public final long extractionId;
        public final long documentTypeId;
        public final int typeVersion;

        public Document(long extractionId, long documentTypeId, int typeVersion) {
            this.extractionId = extractionId;
            this.documentTypeId = documentTypeId;
            this.typeVersion = typeVersion;
        }

        @Override
        public String toString() {
            return "document " + this.extractionId + " (type " + this.documentTypeId + " v" + this.typeVersion + ")";
        }
    }

    /** The run's documents, in the order they were approved; empty when no approval started the run. */
    List<Document> documentsOf(long jobQueueId);
}
