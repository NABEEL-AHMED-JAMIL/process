package process.mongo.documents;


import com.google.gson.Gson;
import org.springframework.data.mongodb.core.mapping.Document;
import process.model.enums.Status;
import org.springframework.data.annotation.Id;


/**
 * @author Nabeel Ahmed
 * File Scraper
 * */
@Document(collection = "scraper")
public class Scraper {

	@Id
	private String scraperId;
	private Long appUserId;
	private Long jobId;
	private Long sourceTaskId;
	private Long jobQueueId;
	private FileInfo oldFile;
	private FileInfo newFile;
	private Boolean newFileFlag;
	private Long acceptedBy;
	private Long rejectedBy;
	private Status status;

	public Scraper() {
	}

	public String getScraperId() {
		return scraperId;
	}

	public void setScraperId(String scraperId) {
		this.scraperId = scraperId;
	}

	public Long getAppUserId() {
		return appUserId;
	}

	public void setAppUserId(Long appUserId) {
		this.appUserId = appUserId;
	}

	public Long getJobId() {
		return jobId;
	}

	public void setJobId(Long jobId) {
		this.jobId = jobId;
	}

	public Long getSourceTaskId() {
		return sourceTaskId;
	}

	public void setSourceTaskId(Long sourceTaskId) {
		this.sourceTaskId = sourceTaskId;
	}

	public Long getJobQueueId() {
		return jobQueueId;
	}

	public void setJobQueueId(Long jobQueueId) {
		this.jobQueueId = jobQueueId;
	}

	public FileInfo getOldFile() {
		return oldFile;
	}

	public void setOldFile(FileInfo oldFile) {
		this.oldFile = oldFile;
	}

	public FileInfo getNewFile() {
		return newFile;
	}

	public void setNewFile(FileInfo newFile) {
		this.newFile = newFile;
	}

	public Boolean getNewFileFlag() {
		return newFileFlag;
	}

	public void setNewFileFlag(Boolean newFileFlag) {
		this.newFileFlag = newFileFlag;
	}

	public Long getAcceptedBy() {
		return acceptedBy;
	}

	public void setAcceptedBy(Long acceptedBy) {
		this.acceptedBy = acceptedBy;
	}

	public Long getRejectedBy() {
		return rejectedBy;
	}

	public void setRejectedBy(Long rejectedBy) {
		this.rejectedBy = rejectedBy;
	}

	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	@Override
	public String toString() {
		return new Gson().toJson(this);
	}

}
