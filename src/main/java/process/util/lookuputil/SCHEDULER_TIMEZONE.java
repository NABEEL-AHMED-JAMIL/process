package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum SCHEDULER_TIMEZONE {

    ASIA_QATAR("ASIA_QATAR", 0l, "(GMT+3:00) Asia/Qatar :: Arabia Standard Time"),
    ASIA_KARACHI("ASIA_KARACHI", 1l, "(GMT+5:00) Asia/Karachi :: Pakistan Time"),
    AMERICA_NEW_YORK("AMERICA_NEW_YORK", 2l, "(GMT-5:00) America/New_York :: Eastern Standard Time");

    private String lookupType;
    private Long lookupValue;
    private String description;

    SCHEDULER_TIMEZONE(String lookupType, Long lookupValue, String description) {
        this.lookupType = lookupType;
        this.lookupValue = lookupValue;
        this.description = description;
    }

    public static GLookup getSchedulerTimeZoneByValue(Long lookupValue) {
        SCHEDULER_TIMEZONE schedulerTimezone = null;
        if (lookupValue.equals(ASIA_QATAR.lookupValue)) {
            schedulerTimezone = ASIA_QATAR;
        } else if (lookupValue.equals(ASIA_KARACHI.lookupValue)) {
            schedulerTimezone = ASIA_KARACHI;
        } else if (lookupValue.equals(AMERICA_NEW_YORK.lookupValue)) {
            schedulerTimezone = AMERICA_NEW_YORK;
        }
        return new GLookup(schedulerTimezone.lookupType, schedulerTimezone.lookupValue, schedulerTimezone.description);
    }

    public static GLookup getSchedulerTimeZoneByLookupType(String description) {
        SCHEDULER_TIMEZONE schedulerTimezone = null;
        if (description.equals(ASIA_QATAR.getDescription())) {
            schedulerTimezone = ASIA_QATAR;
        } else if (description.equals(ASIA_KARACHI.getDescription())) {
            schedulerTimezone = ASIA_KARACHI;
        } else if (description.equals(AMERICA_NEW_YORK.getDescription())) {
            schedulerTimezone = AMERICA_NEW_YORK;
        }
        return new GLookup(schedulerTimezone.lookupType, schedulerTimezone.lookupValue, schedulerTimezone.description);
    }

    public String getLookupType() {
        return lookupType;
    }

    public void setLookupType(String lookupType) {
        this.lookupType = lookupType;
    }

    public Long getLookupValue() {
        return lookupValue;
    }

    public void setLookupValue(Long lookupValue) {
        this.lookupValue = lookupValue;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
