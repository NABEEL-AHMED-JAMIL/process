package process.mongo.documents;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.springframework.data.mongodb.core.mapping.Document;
import javax.persistence.Id;

/**
 * @author Nabeel Ahmed
 * File Detail
 * */
@Document(collection = "fileInfo")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileInfo {

	@Id
	private String id;
	private Long jobId;
	private Long jobQueueId;
	private String lastDate;
	private Boolean fileAccess;
	private Long fileSize;
	private String fileType;
	private String storeFileName; // name for scrap file
	private String storeFileUrl; // where the file store after scrap
	private String remoteFileUrl; // from where we scrap the file
	private String remoteFileTag;
	private String remoteFileName;
	private HashMeta hashMeta;

	public FileInfo() {
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public Long getJobId() {
		return jobId;
	}

	public void setJobId(Long jobId) {
		this.jobId = jobId;
	}

	public Long getJobQueueId() {
		return jobQueueId;
	}

	public void setJobQueueId(Long jobQueueId) {
		this.jobQueueId = jobQueueId;
	}

	public String getLastDate() {
		return lastDate;
	}

	public void setLastDate(String lastDate) {
		this.lastDate = lastDate;
	}

	public Boolean getFileAccess() {
		return fileAccess;
	}

	public void setFileAccess(Boolean fileAccess) {
		this.fileAccess = fileAccess;
	}

	public Long getFileSize() {
		return fileSize;
	}

	public void setFileSize(Long fileSize) {
		this.fileSize = fileSize;
	}

	public String getFileType() {
		return fileType;
	}

	public void setFileType(String fileType) {
		this.fileType = fileType;
	}

	public String getStoreFileName() {
		return storeFileName;
	}

	public void setStoreFileName(String storeFileName) {
		this.storeFileName = storeFileName;
	}

	public String getStoreFileUrl() {
		return storeFileUrl;
	}

	public void setStoreFileUrl(String storeFileUrl) {
		this.storeFileUrl = storeFileUrl;
	}

	public String getRemoteFileUrl() {
		return remoteFileUrl;
	}

	public void setRemoteFileUrl(String remoteFileUrl) {
		this.remoteFileUrl = remoteFileUrl;
	}

	public String getRemoteFileTag() {
		return remoteFileTag;
	}

	public void setRemoteFileTag(String remoteFileTag) {
		this.remoteFileTag = remoteFileTag;
	}

	public String getRemoteFileName() {
		return remoteFileName;
	}

	public void setRemoteFileName(String remoteFileName) {
		this.remoteFileName = remoteFileName;
	}

	public HashMeta getHashMeta() {
		return hashMeta;
	}

	public void setHashMeta(HashMeta hashMeta) {
		this.hashMeta = hashMeta;
	}

	@Override
	public String toString() {
		return new Gson().toJson(this);
	}

}
