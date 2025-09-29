package process.socket.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import process.model.pojo.AppUser;
import process.model.pojo.NotificationAudit;
import process.model.repository.AppUserRepository;
import process.model.repository.NotificationAuditRepository;
import process.model.repository.specification.NotificationSpecification;
import process.payload.request.NotificationRequest;
import process.payload.response.AppResponse;
import process.payload.response.LookupDataResponse;
import process.payload.response.NotificationResponse;
import process.service.LookupDataCacheService;
import process.socket.GlobalProperties;
import process.socket.service.NotificationService;
import process.util.ProcessUtil;
import process.util.lookuputil.LookupDetailUtil;
import process.util.lookuputil.NOTIFICATION_STATUS;
import process.util.lookuputil.APPLICATION_STATUS;
import process.util.lookuputil.NOTIFICATION_TYPE;
import java.util.Optional;
import java.util.function.Function;

/**
 * @author Nabeel Ahmed
 */
@Service
public class NotificationServiceImpl implements NotificationService {

    private Logger logger = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final String REPLAY = "/reply";
    private final String NOTIFY_ID = "notifyId";

    @Autowired
    private GlobalProperties globalProperties;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private LookupDataCacheService lookupDataCacheService;
    @Autowired
    private NotificationAuditRepository notificationAuditRepository;


    /**
     * Method use save notification to user session
     * @param requestPayload
     * @param appUser
     * */
    @Override
    public void addNotification(NotificationRequest requestPayload, AppUser appUser) throws Exception {
        NotificationAudit notificationAudit = new NotificationAudit();
        notificationAudit.setSendTo(appUser);
        notificationAudit.setMessage(requestPayload.getBody());
        notificationAudit.setNotifyType(requestPayload.getNotifyType());
        notificationAudit.setMessageStatus(requestPayload.getMessageStatus());
        notificationAudit.setExpireTime(requestPayload.getExpireTime());
        this.notificationAuditRepository.save(notificationAudit);
    }


    /**
     * updateNotification method use to change the notification status by update the status(read|unread)
     * @param requestPayload
     * */
    @Override
    public AppResponse updateNotification(NotificationRequest requestPayload) throws Exception {
        logger.info("Request findAppUserProfile :- " + requestPayload);
        if (ProcessUtil.isNull(requestPayload.getNotifyId())) {
            return new AppResponse(ProcessUtil.ERROR, "NotifyId missing.");
        }
        Optional<NotificationAudit> notificationAudit = this.notificationAuditRepository.findById(requestPayload.getNotifyId());
        if (!notificationAudit.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Notification audit not found.");
        }
        notificationAudit.get().setMessageStatus(NOTIFICATION_STATUS.READ.getLookupValue());
        this.notificationAuditRepository.save(notificationAudit.get());
        return new AppResponse(ProcessUtil.SUCCESS, "Notification Update successfully.", requestPayload);
    }

    /**
     * fetchAllNotification method use to fetch all notification by user
     * @param username
     * */
    @Override
    public AppResponse fetchAllNotification(String username) throws Exception {
        logger.info("Request findAppUserProfile :- " + username);
        if (ProcessUtil.isNull(username)) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(username, APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        LookupDataResponse notificationTime = this.lookupDataCacheService.getParentLookupById(LookupDetailUtil.NOTIFICATION_DISAPPEAR_TIME);
        Page<NotificationAudit> notificationAuditPage = this.notificationAuditRepository.findAll(new NotificationSpecification(
            appUser.get(), Long.valueOf(notificationTime.getLookupValue())), PageRequest.of(0, 500, Sort.by(Sort.Order.asc(NOTIFY_ID))));
        // convert pojo to dto
        Function<NotificationAudit, NotificationResponse> notificationAuditFunction = notificationAudit -> {
            NotificationResponse notificationRequest = new NotificationResponse();
            notificationRequest.setNotifyId(notificationAudit.getNotifyId());
            notificationRequest.setSendTo(notificationAudit.getSendTo().getUsername());
            notificationRequest.setBody(notificationAudit.getMessage());
            notificationRequest.setNotifyType(NOTIFICATION_TYPE.getStatusByValue(notificationAudit.getNotifyType()));
            notificationRequest.setExpireTime(notificationAudit.getExpireTime());
            notificationRequest.setMessageStatus(NOTIFICATION_STATUS.getStatusByValue(notificationAudit.getMessageStatus()));
            notificationRequest.setCreateDate(notificationAudit.getDateCreated());
            return notificationRequest;
        };
        return new AppResponse(ProcessUtil.SUCCESS, "Notification Result.", notificationAuditPage.map(notificationAuditFunction).getContent());
    }

    /**
     * Notify to websocket
     * @param message
     * @param transactionId
     */
    public void sendNotificationToSpecificUser(String message, String transactionId) {
        logger.info("Request sendNotificationToSpecificUser :- " + message);
        if (!ProcessUtil.isNull(this.globalProperties.getSessionId(transactionId))) {
            String sendTo = this.globalProperties.getSessionId(transactionId)+"-"+transactionId;
            this.messagingTemplate.convertAndSendToUser(sendTo, REPLAY, message);
            logger.info("Session exist, Sending To " + sendTo);
        } else {
            logger.info("No session exist with transactionId " + transactionId);
        }
    }

}