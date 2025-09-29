package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "notification_audit")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationAudit {

    @GenericGenerator(
        name = "notificationAuditSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "notification_audit_source_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        })
    @Id
    @Column(name = "notify_id")
    @GeneratedValue(generator = "notificationAuditSequenceGenerator")
    private Long notifyId;

    @ManyToOne
    @JoinColumn(name = "send_to")
    protected AppUser sendTo;

    @Column(name = "message", nullable = false)
    private String message;

    // job|email
    @Column(name = "notify_type", nullable = false)
    private Long notifyType;

    // base on look-up
    @Column(name = "expire_time", nullable = false)
    private Timestamp expireTime;

    // read|un-read
    @Column(name = "message_state", nullable = false)
    private Long messageStatus;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    public NotificationAudit() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getNotifyId() {
        return notifyId;
    }

    public void setNotifyId(Long notifyId) {
        this.notifyId = notifyId;
    }

    public AppUser getSendTo() {
        return sendTo;
    }

    public void setSendTo(AppUser sendTo) {
        this.sendTo = sendTo;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getNotifyType() {
        return notifyType;
    }

    public void setNotifyType(Long notifyType) {
        this.notifyType = notifyType;
    }

    public Timestamp getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(Timestamp expireTime) {
        this.expireTime = expireTime;
    }

    public Long getMessageStatus() {
        return messageStatus;
    }

    public void setMessageStatus(Long messageStatus) {
        this.messageStatus = messageStatus;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}