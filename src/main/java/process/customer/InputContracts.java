package process.customer;

import com.fasterxml.jackson.databind.JsonNode;
import org.barco.platform.api.Problem;
import process.pipeline.PipelineDefinition;

import java.util.List;

/**
 * A pipeline's input contract (MIG-332, a MIG-233 data contract in integration-service): its JSON Schema, and a record's
 * verdict against it, never recorded -- the customer API asks before anything is stored.
 */
public interface InputContracts {

    /** The contract as resolved and, for a record, whether it holds; the errors' paths are the record's ("record.amount"). */
    final class Checked {
        public Long contractId;
        public String name;
        public Integer version;
        public JsonNode schema;
        /** Null when no record was checked. */
        public Boolean valid;
        public List<Problem.FieldError> errors;
    }

    /** The contract can not be had: it does not exist, or the service that keeps contracts cannot answer. */
    final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * @param record the record to check, or null for the contract alone
     * @param pathPrefix what a path is put under in the answer ("record", "data")
     * @throws Unavailable when the contract cannot be had
     */
    Checked check(long tenantId, PipelineDefinition.ContractRef contract, JsonNode record, String pathPrefix);

    /** "$.patient.mrn" (the validator's) as "record.patient.mrn"; "$" as "record". */
    static String pathOf(String validatorPath, String prefix) {
        if (validatorPath == null || validatorPath.isEmpty() || "$".equals(validatorPath)) {
            return prefix;
        }
        if (validatorPath.startsWith("$.") || validatorPath.startsWith("$[")) {
            return prefix + validatorPath.substring(1);
        }
        return prefix + "." + validatorPath;
    }
}
