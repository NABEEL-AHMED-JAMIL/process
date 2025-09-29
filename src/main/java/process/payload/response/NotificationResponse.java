package process.payload.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.util.lookuputil.GLookup;

import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationResponse {

    private Long notifyId;
    private String sendTo;
    private String body;
    private GLookup notifyType;
    private Timestamp expireTime;
    private GLookup messageStatus;
    private Timestamp createDate;

    public NotificationResponse() {
    }

    public NotificationResponse(String sendTo, String body,
        GLookup notifyType, Timestamp expireTime, GLookup messageStatus) {
        this.sendTo = sendTo;
        this.body = body;
        this.notifyType = notifyType;
        this.expireTime = expireTime;
        this.messageStatus = messageStatus;
    }

    public Long getNotifyId() {
        return notifyId;
    }

    public void setNotifyId(Long notifyId) {
        this.notifyId = notifyId;
    }

    public String getSendTo() {
        return sendTo;
    }

    public void setSendTo(String sendTo) {
        this.sendTo = sendTo;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public GLookup getNotifyType() {
        return notifyType;
    }

    public void setNotifyType(GLookup notifyType) {
        this.notifyType = notifyType;
    }

    public Timestamp getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(Timestamp expireTime) {
        this.expireTime = expireTime;
    }

    public GLookup getMessageStatus() {
        return messageStatus;
    }

    public void setMessageStatus(GLookup messageStatus) {
        this.messageStatus = messageStatus;
    }

    public Timestamp getCreateDate() {
        return createDate;
    }

    public void setCreateDate(Timestamp createDate) {
        this.createDate = createDate;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}