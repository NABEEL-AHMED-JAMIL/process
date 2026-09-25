package process.config;

/**
 * No Kafka connection resolves for a workspace's send (MIG-45). The message is written for the person
 * who reads the run's status line: what is missing and what to set, nothing about tiers or brokers.
 *
 * There is no fallback behind it any more. A send that meets this is refused -- the run fails, the
 * event is not published -- rather than put on whatever brokers the application itself connects to.
 *
 * @author Nabeel Ahmed
 */
public class KafkaRouteUnresolvedException extends IllegalStateException {

    public KafkaRouteUnresolvedException(String message) {
        super(message);
    }
}
