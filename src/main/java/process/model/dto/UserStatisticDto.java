package process.model.dto;

/**
 * What one user owns and how it has gone.
 *
 * Jobs attribute directly through assigned_user_id. Tasks carry no owner in the schema, so
 * taskCount is the distinct tasks this user's jobs point at -- named plainly here so a reader
 * does not take it for something the user is recorded as owning.
 *
 * @author Nabeel Ahmed
 */
public class UserStatisticDto {

    private Long appUserId;
    private String username;
    private String fullName;
    private String userRole;
    private String status;
    private String avatarBucket;
    private String avatarKey;
    private Integer jobCount;
    private Integer activeJobs;
    private Integer taskCount;
    private Integer runCount;
    private Integer completedCount;
    private Integer failedCount;

    public UserStatisticDto() {}

    public Long getAppUserId() { return appUserId; }
    public void setAppUserId(Long appUserId) { this.appUserId = appUserId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getUserRole() { return userRole; }
    public void setUserRole(String userRole) { this.userRole = userRole; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getAvatarBucket() { return avatarBucket; }
    public void setAvatarBucket(String avatarBucket) { this.avatarBucket = avatarBucket; }

    public String getAvatarKey() { return avatarKey; }
    public void setAvatarKey(String avatarKey) { this.avatarKey = avatarKey; }

    public Integer getJobCount() { return jobCount; }
    public void setJobCount(Integer jobCount) { this.jobCount = jobCount; }

    public Integer getActiveJobs() { return activeJobs; }
    public void setActiveJobs(Integer activeJobs) { this.activeJobs = activeJobs; }

    public Integer getTaskCount() { return taskCount; }
    public void setTaskCount(Integer taskCount) { this.taskCount = taskCount; }

    public Integer getRunCount() { return runCount; }
    public void setRunCount(Integer runCount) { this.runCount = runCount; }

    public Integer getCompletedCount() { return completedCount; }
    public void setCompletedCount(Integer completedCount) { this.completedCount = completedCount; }

    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
}
