package process.util;

import process.model.enums.Execution;
import process.model.enums.JobStatus;
import process.model.enums.Status;

@Deprecated
public class EnumConverter {

    public static Status toStatus(String value) {
        return EnumUtils.parseEnum(Status.class, value);
    }

    public static JobStatus toJobStatus(String value) {
        return EnumUtils.parseEnum(JobStatus.class, value);
    }

    public static Execution toExecution(String value) {
        return EnumUtils.parseEnum(Execution.class, value);
    }

}
