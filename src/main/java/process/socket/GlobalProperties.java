package process.socket;

import org.springframework.stereotype.Component;
import java.util.HashMap;

/**
 * @author Nabeel Ahmed
 */
@Component
public class GlobalProperties {

    private HashMap<String, String> transactionSessionMap = new HashMap<>();
    private HashMap<String, String> sessionTransactionMap = new HashMap<>();

    /**
     * Method use to add the new session with transaction
     * @param sessionId
     * @param transactionId
     * */
    public void addTransactionAndSession(String sessionId, String transactionId) {
        this.sessionTransactionMap.put(sessionId, transactionId);
        this.transactionSessionMap.put(transactionId, sessionId);
    }

    /**
     * Method use to remove the transaction
     * @param sessionId
     * */
    public void removeTransactionAndSession(String sessionId) {
        String transactionId = sessionTransactionMap.get(sessionId);
        this.sessionTransactionMap.remove(sessionId);
        this.transactionSessionMap.remove(transactionId);
    }

    /**
     * Method use to get the sessin by trasction id
     * @param transactionId
     * @return session
     * */
    public String getSessionId(String transactionId) {
        return this.transactionSessionMap.get(transactionId);
    }

}