package process.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The gate refuses a live database by its name, before it connects to anything (MIG-129). */
class SchemaGateTest {

    @Test
    void aDatabaseWithoutGateInItsNameIsRefusedBeforeAnyConnection() {
        assertThatThrownBy(() -> SchemaGate.open("jdbc:postgresql://localhost:1/etl_job", "nobody", "nothing"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("etl_job, which is not a _gate_ database");
    }
}
