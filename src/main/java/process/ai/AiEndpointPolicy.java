package process.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.model.dto.ResponseDto;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.isNull;

/**
 * Which endpoints the server will call on a person's say-so. A model connection's endpoint
 * arrives from the browser and the provider's answer is handed back, so without this rule
 * the AI screens would be a way to read anything the server can reach on its own network.
 * Public https hosts are fine; private, loopback and plain-http addresses are not, except
 * the hosts named in `ai.allowed-endpoint-hosts` (the local Ollama, by default).
 */
@Component
public class AiEndpointPolicy {

    /** Said the same way whichever rule refused, so the answer carries no map of the network. */
    private static final String ENDPOINT_NOT_ALLOWED = "That apiEndpoint is not an allowed AI provider address.";

    @Value("${ai.allowed-endpoint-hosts:host.docker.internal}")
    private String allowedEndpointHosts;

    /** Null when the endpoint may be called; otherwise the refusal to hand back. */
    public ResponseDto validateEndpoint(String apiEndpoint) {
        URI uri;
        try {
            uri = new URI(apiEndpoint.trim());
        } catch (URISyntaxException ex) {
            return new ResponseDto(ERROR, "apiEndpoint is not a valid URL.");
        }
        String scheme = isNull(uri.getScheme()) ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (isNull(host) || host.trim().isEmpty()
            || (!"http".equals(scheme) && !"https".equals(scheme))) {
            return new ResponseDto(ERROR, "apiEndpoint must be an http or https URL naming a host.");
        }
        if (this.allowedHosts().contains(host.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (!"https".equals(scheme)) {
            return new ResponseDto(ERROR, ENDPOINT_NOT_ALLOWED);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            // A name the server cannot resolve is not a provider it can call either way.
            return new ResponseDto(ERROR, ENDPOINT_NOT_ALLOWED);
        }
        for (int i = 0; i < addresses.length; i++) {
            if (isInternalAddress(addresses[i])) {
                return new ResponseDto(ERROR, ENDPOINT_NOT_ALLOWED);
            }
        }
        return null;
    }

    private Set<String> allowedHosts() {
        Set<String> hosts = new HashSet<>();
        if (isNull(this.allowedEndpointHosts) || this.allowedEndpointHosts.trim().isEmpty()) {
            return hosts;
        }
        for (String host : this.allowedEndpointHosts.split(",")) {
            if (!host.trim().isEmpty()) {
                hosts.add(host.trim().toLowerCase(Locale.ROOT));
            }
        }
        return hosts;
    }

    /**
     * Whether an address belongs to the network rather than the internet.
     *
     * InetAddress covers loopback, link-local, the IPv4 private ranges and multicast; the two
     * added by hand are carrier-grade NAT and the IPv6 unique-local range, which are private in
     * practice and which it does not classify.
     */
    private static boolean isInternalAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isAnyLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }
        byte[] octets = address.getAddress();
        if (octets.length == 4) {
            int first = octets[0] & 0xFF;
            int second = octets[1] & 0xFF;
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }
        return octets.length == 16 && (octets[0] & 0xFE) == 0xFC;
    }

}
