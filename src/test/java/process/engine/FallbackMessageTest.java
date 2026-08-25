package process.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import process.model.pojo.JobQueue;
import process.model.enums.JobStatus;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The text a run gets when it ended without reporting one.
 *
 * This mattered on the Reports screen: twenty-two runs read "Failed" beside "Job 1196 now
 * complete.", because the placeholder assumed success regardless of how the run had actually
 * ended. A reader cannot tell a contradiction like that from a bug in the report itself.
 */
class FallbackMessageTest {

    private String fallbackFor(JobStatus status) throws Exception {
        JobQueue queue = new JobQueue();
        queue.setJobId(1196L);
        queue.setJobStatus(status);
        Method method = BulkAction.class.getDeclaredMethod("fallbackMessage", JobQueue.class);
        method.setAccessible(true);
        return (String) method.invoke(null, queue);
    }

    @Test
    @DisplayName("a completed run still reads as complete")
    void completed() throws Exception {
        assertEquals("Job 1196 now complete.", fallbackFor(JobStatus.Completed));
    }

    @Test
    @DisplayName("a failed run never claims to have completed")
    void failedDoesNotClaimSuccess() throws Exception {
        String message = fallbackFor(JobStatus.Failed);
        assertFalse(message.toLowerCase().contains("complete"), message);
        assertTrue(message.contains("failed"), message);
        // and it points somewhere useful, since the reason was not recorded
        assertTrue(message.toLowerCase().contains("logs"), message);
    }

    @Test
    @DisplayName("an interrupted run says it was interrupted")
    void interrupted() throws Exception {
        String message = fallbackFor(JobStatus.Interrupt);
        assertFalse(message.toLowerCase().contains("complete"), message);
        assertTrue(message.toLowerCase().contains("interrupted"), message);
    }

    @Test
    @DisplayName("no status at all is reported as such rather than guessed")
    void missingStatus() throws Exception {
        JobQueue queue = new JobQueue();
        queue.setJobId(7L);
        Method method = BulkAction.class.getDeclaredMethod("fallbackMessage", JobQueue.class);
        method.setAccessible(true);
        String message = (String) method.invoke(null, queue);
        assertFalse(message.toLowerCase().contains("complete"), message);
    }

    @Test
    @DisplayName("every status names the job it is about")
    void namesTheJob() throws Exception {
        for (JobStatus status : new JobStatus[]{
                JobStatus.Completed, JobStatus.Failed, JobStatus.Interrupt, JobStatus.Skip}) {
            assertTrue(fallbackFor(status).contains("1196"), status.toString());
        }
    }
}
