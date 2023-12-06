package process.mongo.documents;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 * File Detail
 * */
public class FileInfo {

	private Boolean fileAccess;
	private Long fileSize;
	private String fileType;
	private String storeFileName;
	private String storeFileUrl; // where the file store after scrap
	private String remoteFileUrl; // from where we scrap the file
	private String remoteFileName;
	private HashMeta hashMeta;

	public FileInfo() {
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
