package process.pipeline.review;

/**
 * A run's review status (MIG-237). NOT_REQUIRED when its pipeline asks for no review (settings.review.required is
 * empty or absent); otherwise PENDING -- a draft -- until every required party has approved (APPROVED) or one has
 * rejected (REJECTED). A run that requires review is never approved by itself.
 *
 * NOT_APPLICABLE (AI hard-workflow test, 2026-10-13): the pipeline asks for a review, but the run ended without results to
 * review -- Failed, Skip, Interrupt or Missed -- and nobody decided: nothing waits for a reviewer ("Failed · waiting for
 * review" before). Worked out, never stored: only a completed run is decided, so run_review never holds it, and
 * ck_run_review_status_enum keeps its four values.
 *
 * Stored by name in run_review.status; ck_run_review_status_enum lists the stored values (RunReviewSchemaPostgresTest
 * pins both). A result_record's own status is {@link process.model.enums.ReviewStatus}, which has no NOT_REQUIRED.
 */
public enum RunReviewStatus {
    NOT_REQUIRED, PENDING, APPROVED, REJECTED, NOT_APPLICABLE;

    /** Whether run_review may hold it: every status but NOT_APPLICABLE, which is only ever worked out. */
    public boolean isStored() {
        return this != NOT_APPLICABLE;
    }

    public boolean isDecided() {
        return this == APPROVED || this == REJECTED;
    }
}
