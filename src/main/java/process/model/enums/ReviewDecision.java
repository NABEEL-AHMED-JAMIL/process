package process.model.enums;

/**
 * One party's decision on a result (MIG-225). Stored by name in result_review.decision; ck_result_review_decision_enum
 * lists the same values (RoundTripExecutionSchemaPostgresTest pins both).
 */
public enum ReviewDecision {
    APPROVED, REJECTED
}
