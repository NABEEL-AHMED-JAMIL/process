package process.pipeline.approval;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import process.model.dto.ResponseDto;
import process.model.enums.ReviewDecision;
import process.pipeline.review.RunReviewService;
import process.pipeline.review.RunReviewTaskListener;
import process.util.UserNameResolver;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MIG-346: workflow-service's instance-changed event for a request of the pipeline-step-approval workflow
 * ("pipeline-approval:N") that ended Approved or Rejected is the Approval step's decision -- who, what they said, which
 * request and task -- and never a run review's; anything else of that workflow is passed over.
 */
class ApprovalDecisionListenerTest {

    private final RunReviewService reviews = mock(RunReviewService.class);
    private final UserNameResolver names = mock(UserNameResolver.class);
    private final RunApprovals approvals = mock(RunApprovals.class);
    private final RunReviewTaskListener listener = new RunReviewTaskListener(this.reviews, this.names, mock(PlatformTransactionManager.class));

    ApprovalDecisionListenerTest() {
        this.listener.setApprovals(this.approvals);
        when(this.names.namesFor(any())).thenReturn(Collections.singletonMap(4641L, "Ana Ruiz"));
    }

    private static String change(String definitionKey, String subject, String state, String decision) {
        return "{\"eventType\":\"workflow.instance.changed\",\"payload\":{\"tenantId\":2960,\"instanceId\":77,\"definitionKey\":\"" + definitionKey
            + "\",\"subjectId\":\"" + subject + "\",\"state\":\"" + state + "\"" + (decision == null ? "" : ",\"decision\":" + decision) + "}}";
    }

    @Test
    void anApprovedRequestContinuesTheRunAndARejectedOneFailsIt() {
        this.listener.onChange(change("pipeline-step-approval", "pipeline-approval:1203", "Approved",
            "{\"actedBy\":4641,\"comment\":\"ok\",\"taskId\":88}"));
        verify(this.approvals).decideFromInbox(2960L, 1203L, true, 4641L, "Ana Ruiz", "ok", 77L, 88L, null);
        this.listener.onChange(change("pipeline-step-approval", "pipeline-approval:1204", "Rejected",
            "{\"actedBy\":4641,\"comment\":\"wrong totals\",\"taskId\":89}"));
        verify(this.approvals).decideFromInbox(2960L, 1204L, false, 4641L, "Ana Ruiz", "wrong totals", 77L, 89L, null);
        verifyNoInteractions(this.reviews);
    }

    @Test
    void anOpenOrMalformedRequestIsPassedOver() {
        this.listener.onChange(change("pipeline-step-approval", "pipeline-approval:1203", "Running", null));
        this.listener.onChange(change("pipeline-step-approval", "pipeline-approval:abc", "Approved", "{\"actedBy\":1}"));
        this.listener.onChange(change("pipeline-step-approval", "pipeline-run:5", "Approved", "{\"actedBy\":1}"));
        this.listener.onChange("not json");
        verify(this.approvals, never()).decideFromInbox(anyLong(), anyLong(), anyBoolean(), any(), any(), any(), anyLong(), anyLong(), any());
        verifyNoInteractions(this.reviews);
    }

    /** Bulk review: a task decided in a bulk decision names it (decision.bulkId), and the decision is recorded with it. */
    @Test
    void aBulkDecisionsIdIsPassedOnToTheApprovalAndToTheRunsReview() {
        this.listener.onChange(change("pipeline-step-approval", "pipeline-approval:1205", "Approved",
            "{\"actedBy\":4641,\"comment\":\"ok\",\"taskId\":90,\"bulkId\":\"6f1c2b9e-0d4a-4b7e-9a51-3c2f1e0b7a11\"}"));
        verify(this.approvals).decideFromInbox(2960L, 1205L, true, 4641L, "Ana Ruiz", "ok", 77L, 90L,
            "6f1c2b9e-0d4a-4b7e-9a51-3c2f1e0b7a11");

        when(this.reviews.decideFromInbox(anyLong(), anyLong(), any(), any(), any(), any(), anyLong(), anyLong(), any()))
            .thenReturn(new ResponseDto("SUCCESS", "recorded"));
        this.listener.onChange(change("pipeline-run-review", "pipeline-run:8301", "Rejected",
            "{\"actedBy\":4641,\"comment\":\"totals are off\",\"taskId\":91,\"bulkId\":\"bulk-0001-abcd\"}"));
        verify(this.reviews).decideFromInbox(eq(2960L), eq(8301L), eq(ReviewDecision.REJECTED), eq(4641L), eq("Ana Ruiz"),
            eq("totals are off"), eq(77L), eq(91L), eq("bulk-0001-abcd"));
    }

    /** A single decision's bulkId is absent or null; one not of a bulk id's shape is not recorded. */
    @Test
    void aSingleDecisionOrAMalformedBulkIdIsRecordedWithoutOne() {
        this.listener.onChange(change("pipeline-step-approval", "pipeline-approval:1206", "Approved",
            "{\"actedBy\":4641,\"comment\":\"ok\",\"taskId\":92,\"bulkId\":null}"));
        this.listener.onChange(change("pipeline-step-approval", "pipeline-approval:1207", "Approved",
            "{\"actedBy\":4641,\"comment\":\"ok\",\"taskId\":93,\"bulkId\":\"no spaces; allowed\"}"));
        verify(this.approvals).decideFromInbox(eq(2960L), eq(1206L), eq(true), eq(4641L), anyString(), anyString(), eq(77L), eq(92L), isNull());
        verify(this.approvals).decideFromInbox(eq(2960L), eq(1207L), eq(true), eq(4641L), anyString(), anyString(), eq(77L), eq(93L), isNull());
    }
}
