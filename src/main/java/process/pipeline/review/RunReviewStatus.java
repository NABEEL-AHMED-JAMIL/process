package process.pipeline.review;

/**
 * A run's review status (MIG-237). NOT_REQUIRED when its pipeline asks for no review (settings.review.required is
 * empty or absent); otherwise PENDING -- a draft -- until every required party has approved (APPROVED) or one has
 * rejected (REJECTED). A run that requires review is never approved by itself.
 *
 * Stored by name in run_review.status; ck_run_review_status_enum lists the same values (RunReviewSchemaPostgresTest
 * pins both). A result_record's own status is {@link process.model.enums.ReviewStatus}, which has no NOT_REQUIRED.
 */
public enum RunReviewStatus {
    NOT_REQUIRED, PENDING, APPROVED, REJECTED;

    public boolean isDecided() {
        return this == APPROVED || this == REJECTED;
    }
}
