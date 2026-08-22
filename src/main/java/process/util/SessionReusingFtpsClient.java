package process.util;

import org.apache.commons.net.ftp.FTPSClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.util.Locale;

/**
 * An FTPSClient that reuses the control connection's TLS session on the data connection.
 *
 * Hardened FTPS servers require this: the data channel must resume the control channel's TLS
 * session, proving it belongs to the same client rather than an attacker who observed the
 * PASV port. Servers enforcing it reject an independently negotiated data connection with
 * "425 Cannot secure data connection - TLS session resumption required" (test.rebex.net does
 * exactly this, and so do many production servers such as vsftpd with require_ssl_reuse).
 *
 * Commons Net 3.6 negotiates a fresh session per data connection and offers no setting for
 * this, so the session cache is primed by reflection -- the approach the Commons Net issue
 * tracker itself documents (NET-408). If the internals aren't reachable on the running JDK
 * the socket is left as-is: the connection then behaves exactly as it did before, so a server
 * that doesn't require resumption is unaffected either way.
 */
public class SessionReusingFtpsClient extends FTPSClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionReusingFtpsClient.class);
    private static volatile boolean warnedOnce = false;

    public SessionReusingFtpsClient(boolean implicitTls) {
        super(implicitTls);
        // TLS 1.3 replaced session-ID resumption with PSK tickets, so priming the session cache
        // below has no effect there and a server demanding reuse still refuses the transfer
        // (verified against test.rebex.net: identical request succeeds on 1.2, fails on 1.3).
        // Pinning FTPS to 1.2 is what makes resumption reachable at all; 1.2 remains current
        // and is what most FTPS servers negotiate anyway. This applies to FTPS connections
        // only and has no bearing on the rest of the application's TLS.
        this.setEnabledProtocols(new String[] { "TLSv1.2" });
    }

    @Override
    protected void _prepareDataSocket_(final Socket socket) throws IOException {
        if (!(socket instanceof SSLSocket)) {
            return;
        }
        SSLSession controlSession = ((SSLSocket) this._socket_).getSession();
        if (controlSession == null || !controlSession.isValid()) {
            return;
        }
        try {
            SSLSessionContext context = controlSession.getSessionContext();
            if (context == null) {
                return;
            }
            Field cacheField = context.getClass().getDeclaredField("sessionHostPortCache");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(context);
            java.lang.reflect.Method put = cache.getClass().getDeclaredMethod(
                "put", Object.class, Object.class);
            put.setAccessible(true);

            String key = String.format("%s:%s",
                socket.getInetAddress().getHostName(), String.valueOf(socket.getPort()))
                .toLowerCase(Locale.ROOT);
            put.invoke(cache, key, controlSession);

            String hostAddressKey = String.format("%s:%s",
                socket.getInetAddress().getHostAddress(), String.valueOf(socket.getPort()))
                .toLowerCase(Locale.ROOT);
            put.invoke(cache, hostAddressKey, controlSession);
        } catch (Exception e) {
            // Deliberately non-fatal. On a JDK that blocks reflection into sun.security.ssl
            // this is expected, and failing here would break every FTPS connection including
            // the ones that never needed resumption. Without it the data connection still
            // opens; only a server that mandates reuse refuses it, and that refusal now
            // surfaces as a real error rather than an empty listing.
            if (!warnedOnce) {
                warnedOnce = true;
                LOGGER.warn("FTPS data connections can't reuse the control session on this JVM ({}). "
                    + "Servers requiring TLS session resumption will reject transfers; add "
                    + "--add-opens for java.base/sun.security.ssl and java.base/sun.security.util "
                    + "to enable it.", e.getMessage());
            }
        }
    }

}
