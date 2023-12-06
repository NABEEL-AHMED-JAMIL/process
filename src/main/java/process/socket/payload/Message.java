package process.socket.payload;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public class Message {

    private String sessionId;
    private String transactionId;

    public Message() {
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
