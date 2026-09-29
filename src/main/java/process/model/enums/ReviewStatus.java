package process.model.enums;

/**
 * A result's review state (MIG-225, feature plan 21.1): PENDING until every required review has passed (APPROVED) or
 * one party rejects it (REJECTED; a re-run makes a new result). Every result starts PENDING -- a draft until reviewed.
 * Stored by name in result_record.review_status; ck_result_record_review_status_enum lists the same values
 * (RoundTripExecutionSchemaPostgresTest pins both).
 */
public enum ReviewStatus {
    PENDING, APPROVED, REJECTED
}
