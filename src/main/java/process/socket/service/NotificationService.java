package process.socket.service;

import process.model.pojo.AppUser;
import process.payload.request.NotificationRequest;
import process.payload.response.AppResponse;

/**
 * @author Nabeel Ahmed
 */
public interface NotificationService {

    public void addNotification(NotificationRequest requestPayload, AppUser appUser) throws Exception;

    public AppResponse updateNotification(NotificationRequest requestPayload) throws Exception ;

    public AppResponse fetchAllNotification(String username) throws Exception ;

    public void sendNotificationToSpecificUser(String message, String transactionId);

}