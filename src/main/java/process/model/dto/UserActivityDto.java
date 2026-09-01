package process.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What the signed-in person has been doing, for the profile screen.
 *
 * Gathered here rather than in the browser because the screen used to fetch every job in the
 * tenant and filter it down to the caller by matching a username string -- which meant the page
 * cost grew with the whole workspace to show a handful of rows, and matched on a field that is not
 * the identity. The counts and the runs come from one query against the caller's own id.
 *
 * @author Nabeel Ahmed
 * */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserActivityDto {

    private long jobsAssigned;
    private long activeJobs;
    /** Runs in the window below, so the figure has a stated period rather than meaning "ever". */
    private long recentRuns;
    private long recentFailures;
    private int windowDays;

    private List<Run> runs = new ArrayList<>();
    /** How their jobs last ran, most common first, for the donut on the same screen. */
    private List<Outcome> outcomes = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Outcome {
        private String name;
        private long value;

        public Outcome() { }

        public Outcome(String name, long value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return this.name; }
        public void setName(String name) { this.name = name; }

        public long getValue() { return this.value; }
        public void setValue(long value) { this.value = value; }
    }

    /**
     * One run of one job.
     *
     * Carries the times rather than a formatted duration: how to say "4 minutes" belongs to
     * whoever is rendering it, and a server that decides is a server that has to be changed when
     * the wording does.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Run {

        private Long jobQueueId;
        private Long jobId;
        private String jobName;
        private String jobStatus;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        /** Only worth carrying when something went wrong; a success has nothing to explain. */
        private String jobStatusMessage;

        public Long getJobQueueId() { return this.jobQueueId; }
        public void setJobQueueId(Long jobQueueId) { this.jobQueueId = jobQueueId; }

        public Long getJobId() { return this.jobId; }
        public void setJobId(Long jobId) { this.jobId = jobId; }

        public String getJobName() { return this.jobName; }
        public void setJobName(String jobName) { this.jobName = jobName; }

        public String getJobStatus() { return this.jobStatus; }
        public void setJobStatus(String jobStatus) { this.jobStatus = jobStatus; }

        public LocalDateTime getStartTime() { return this.startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

        public LocalDateTime getEndTime() { return this.endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

        public String getJobStatusMessage() { return this.jobStatusMessage; }
        public void setJobStatusMessage(String jobStatusMessage) { this.jobStatusMessage = jobStatusMessage; }
    }

    public long getJobsAssigned() { return this.jobsAssigned; }
    public void setJobsAssigned(long jobsAssigned) { this.jobsAssigned = jobsAssigned; }

    public long getActiveJobs() { return this.activeJobs; }
    public void setActiveJobs(long activeJobs) { this.activeJobs = activeJobs; }

    public long getRecentRuns() { return this.recentRuns; }
    public void setRecentRuns(long recentRuns) { this.recentRuns = recentRuns; }

    public long getRecentFailures() { return this.recentFailures; }
    public void setRecentFailures(long recentFailures) { this.recentFailures = recentFailures; }

    public int getWindowDays() { return this.windowDays; }
    public void setWindowDays(int windowDays) { this.windowDays = windowDays; }

    public List<Run> getRuns() { return this.runs; }
    public void setRuns(List<Run> runs) { this.runs = runs; }

    public List<Outcome> getOutcomes() { return this.outcomes; }
    public void setOutcomes(List<Outcome> outcomes) { this.outcomes = outcomes; }

}
