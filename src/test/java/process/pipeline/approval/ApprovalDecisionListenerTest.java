package process.pipeline.approval;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import process.pipeline.review.RunReviewService;
import process.pipeline.review.RunReviewTaskListener;
import process.util.UserNameResolver;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
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
        verify(this.approvals).decideFromInbox(2960L, 1203L, true, 4641L, "Ana Ruiz", "ok", 77L, 88L);
        this.listener.onChange(change("pipeline-step-approval", "pipeline-approval:1204", "Rejected",
            "{\"actedBy\":4641,\"comment\":\"wrong totals\",\"taskId\":89}"));
        verify(this.approvals).decideFromInbox(2960L, 1204L, false, 4641L, "Ana Ruiz", "wrong totals", 77L, 89L);
        verifyNoInteractions(this.reviews);
    }

    @Test
    void anOpenOrMalformedRequestIsPassedOver() {
        this.listener.onChange(change("pipeline-step-approval", "pipeline-approval:1203", "Running", null));
        this.listener.onChange(change("pipeline-step-approval", "pipeline-approval:abc", "Approved", "{\"actedBy\":1}"));
        this.listener.onChange(change("pipeline-step-approval", "pipeline-run:5", "Approved", "{\"actedBy\":1}"));
        this.listener.onChange("not json");
        verify(this.approvals, never()).decideFromInbox(anyLong(), anyLong(), anyBoolean(), any(), any(), any(), anyLong(), anyLong());
        verifyNoInteractions(this.reviews);
    }
}
