package process.pipeline.registry;

import com.fasterxml.jackson.annotation.JsonValue;

/** What a registered task does with a pipeline's rows (MIG-231): the console's "Add step" groups by it. */
public enum TaskKind {

    /** Brings rows in: from an API, a bucket, a database, or the step itself. Its input is ignored. */
    READ("Read"),
    /** Turns its input into its output inside Core, or with another service's help (a contract, an API). */
    PROCESS("Process"),
    /** Sends its input somewhere -- a file, a bucket, a database, people -- and passes it on unchanged. */
    OUTPUT("Output"),
    /** An existing pipeline, run by its worker exactly as before. */
    LEGACY("Legacy");

    private final String label;

    TaskKind(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return this.label;
    }
}
