package process.pipeline.backing;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * integration-service's data contracts, as the step engine asks them (MIG-231: Validate). The contract and its rules
 * are integration-service's; a step sends its rows and reads a verdict per row.
 */
public interface ContractChecker {

    /** Rows per call: a step with more sends them in batches. */
    int BATCH = 500;

    Optional<String> unavailable();

    ContractVerdicts validate(ContractCall call) throws Exception;

    /** A contract by id, or by name (the workspace's, else a system contract), at a version or the active one. */
    final class ContractCall {
        public long tenantId;
        public long jobQueueId;
        public String stepKey;
        public Long contractId;
        public String contractName;
        public Integer version;
        public List<Map<String, Object>> rows;
    }

    final class ContractVerdicts {
        public Long contractId;
        public String name;
        public Integer version;
        /** One per row sent, by its index in the batch. */
        public List<RowVerdict> rows;
    }

    final class RowVerdict {
        public int index;
        public boolean valid;
        /** "path: message", never a value. */
        public List<String> errors;

        public RowVerdict() {
        }

        public RowVerdict(int index, boolean valid, List<String> errors) {
            this.index = index;
            this.valid = valid;
            this.errors = errors;
        }
    }
}
