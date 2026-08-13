package process.util.exception;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.ArrayList;
import java.util.List;

public class ExceptionUtil {

    public Logger logger = LogManager.getLogger(ExceptionUtil.class);

    public static Throwable getRootCause(final Throwable throwable) {
        final List<Throwable> list = getThrowableList(throwable);
        Throwable rootCause = list.size() < 2 ? null : (Throwable) list.get(list.size() - 1);
        if (rootCause == null) {
            return throwable;
        }
        return rootCause;
    }

    public static String getRootCauseMessage(final Throwable throwable) {
        Throwable root = getRootCause(throwable);
        return root.toString();
    }

    private static List<Throwable> getThrowableList(Throwable throwable) {
        final List<Throwable> list = new ArrayList<Throwable>();
        while (throwable != null && !list.contains(throwable)) {
            list.add(throwable);
            throwable = throwable.getCause();
        }
        return list;
    }
}