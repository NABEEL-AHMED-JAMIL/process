package process.mongo.documents;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 * File HashDetail
 * */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HashMeta {

	private String hashAlgo;
	private String hashTag;
	private String hashFileUrl;

	public String getHashAlgo() {
		return hashAlgo;
	}

	public void setHashAlgo(String hashAlgo) {
		this.hashAlgo = hashAlgo;
	}

	public String getHashTag() {
		return hashTag;
	}

	public void setHashTag(String hashTag) {
		this.hashTag = hashTag;
	}

	public String getHashFileUrl() {
		return hashFileUrl;
	}

	public void setHashFileUrl(String hashFileUrl) {
		this.hashFileUrl = hashFileUrl;
	}

	@Override
	public String toString() {
		return new Gson().toJson(this);
	}

}