package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import process.util.ProcessTimeUtil;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This JobDetailValidation validate the information of the sheet
 * if the date not valid its stop the process and through the valid msg
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobDetailValidation {

    private Logger logger = LoggerFactory.getLogger(JobDetailValidation.class);

    // detail for advance validation
    private List<String> frequencyDetail = ProcessTimeUtil.frequency;
    private Set<String> triggerDetails = ProcessTimeUtil.triggerDetail;
    private Map<String, List<?>> frequencyDetailByTime = ProcessTimeUtil.frequencyDetail;
    // use to split the time
    private String split = ":";
    // time format validation
    private String timeFormat = "^(0[0-9]|1[0-9]|2[0-3]):([0-5][0-9])$";
    // date format validation
    private String dateFormat = "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[1-2][0-9]|3[0-1])$";
    // date parse format
    private String parseDateFormat = "yyyy-MM-dd";

    // required
    private String jobName;
    // required
    private String triggerDetail;
    // required
    private String startDate;
    // optional
    private String endDate;
    // required
    private String startTime;
    // required
    private String frequency;
    // yes for all only no for daily
    private String recurrence;

    // these are ext field for validation
    private String errorMsg; // no

    public JobDetailValidation() { }

    public String getJobName() {
        return jobName;
    }
    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getTriggerDetail() {
        return triggerDetail;
    }
    public void setTriggerDetail(String triggerDetail) {
        this.triggerDetail = triggerDetail;
    }

    public String getStartDate() {
        return startDate;
    }
    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public String getEndDate() {
        return endDate;
    }
    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public String getStartTime() {
        return startTime;
    }
    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getFrequency() {
        return frequency;
    }
    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }

    public String getRecurrence() {
        return recurrence;
    }
    public void setRecurrence(String recurrence) {
        this.recurrence = recurrence;
    }

    public String getErrorMsg() {
        return errorMsg;
    }
    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    /**
     * This isValidJobDetail use to validate the job detail of the job valid return true
     * if non valid return false
     * @return boolean true|false
     * */
    public boolean isValidJobDetail() {
        if (this.isNull(this.jobName)) {
            this.errorMsg = "JobName should not be empty at row %s.";
            return false;
        } else if (this.isNull(this.triggerDetail)) {
            this.errorMsg = "TriggerDetail should not be empty at row %s.";
            return false;
        } else if (this.isNull(this.startDate)) {
            this.errorMsg = "StartDate should not be empty at row %s.";
            return false;
        } else if (this.isNull(this.startTime)) {
            this.errorMsg = "StartTime should not be empty at row %s.";
            return false;
        } else if (this.isNull(this.frequency)) {
            this.errorMsg = "Frequency should not be empty at row %s.";
            return false;
        } else if (this.isValidTriggerDetail()) {
            this.errorMsg = "Invalid TriggerDetail at row %s.";
            return false;
        } else if (this.isValidPattern(this.startDate, this.dateFormat)) {
            this.errorMsg = "Invalid StartDate at row %s.";
            return false;
        } else if (this.isValidPattern(this.startTime, this.timeFormat)) {
            this.errorMsg = "Invalid StartTime at row %s.";
            return false;
        } else if (this.isValidFrequency()) {
            this.errorMsg = String.format("Frequency not valid its should be %s",
            this.frequencyDetail.toString()) + " at row %s.";
            return false;
        } else if (!this.isNull(this.endDate) && this.isValidPattern(this.endDate, this.dateFormat)) {
            this.errorMsg = "Invalid EndDate at row %s.";
            return false;
            // Process for validate the Mint data
        } else if (this.frequency.equals(frequencyDetail.get(0))) {
            return this.isValidMintANDHrDetail();
            // Process for validate the Hr data
        } else if (this.frequency.equals(frequencyDetail.get(1))) {
              return this.isValidMintANDHrDetail();
            // Process for validate the Daily data
        } else if (this.frequency.equals(frequencyDetail.get(2))) {
              return this.isValidDailyDetail();
            // Process for validate the Weekly data
        } else if (this.frequency.equals(frequencyDetail.get(3))) {
              return this.isValidWeeklyDetail();
            // Process for validate the Monthly data
        } else if (this.frequency.equals(frequencyDetail.get(4))) {
            return this.isValidMonthlyDetail();
        }
        this.errorMsg = "Frequency Detail should not be empty at row %s.";
        return false;
    }

    /**
     * This isValidMintANDHrDetail validate detail for mint & hr
     * if the detail are valid then its return true if not then false
     * @return boolean true|false
     * */
    private boolean isValidMintANDHrDetail() {
        try {
            if (this.isNull(this.recurrence)) {
                this.errorMsg = "Recurrence should not be empty at row %s.";
                return false;
            } else if (!this.frequencyDetailByTime.get(this.frequency).stream()
                .filter(x -> x.equals(Integer.valueOf(this.recurrence)))
                .findFirst().isPresent()) {
                this.errorMsg = String.format("Recurrence not valid its should be %s",
                this.frequencyDetailByTime.get(this.frequency)) + " at row %s.";
                return false;
            } else {
                return this.dateTimeValidation(false, false);
            }
        } catch (Exception ex) {
            this.errorMsg = "Issue with (Start Date,End Date,Start Time,Recurrence) at row %s.";
            return false;
        }
    }

    /**
     * This isValidDailyDetail use to validate the daily detail
     * if valid then return true if not then false
     * @return boolean true|false
     * */
    private boolean isValidDailyDetail() {
        try {
            if (!this.isNull(this.recurrence)) {
                this.errorMsg = "Recurrence should be empty for daily frequency at row %s.";
                return false;
            } else {
                return this.dateTimeValidation(false, false);
            }
        } catch (Exception ex) {
            this.errorMsg = "Issue with (Start Date,End Date,Start Time,Recurrence) at row %s.";
            return false;
        }
    }

    /**
     * This isValidWeeklyDetail use to validate detail form weekly detail
     * */
     private boolean isValidWeeklyDetail() {
         try {
             if (this.isNull(this.recurrence)) {
                 this.errorMsg = "Recurrence should not be empty at row %s.";
                 return false;
             } else if (!this.frequencyDetailByTime.get(this.frequency).stream()
                 .filter(x -> x.equals(this.recurrence))
                 .findFirst().isPresent()) {
                 this.errorMsg = String.format("Recurrence not valid its should be %s",
                 this.frequencyDetailByTime.get(this.frequency)) + " at row %s.";
                 return false;
             } else {
                 return this.dateTimeValidation(true, false);
             }
         } catch (Exception ex) {
             this.errorMsg = "Issue with (Start Date,End Date,Start Time,Recurrence) at row %s.";
             return false;
         }
     }

    /**
     * Validation Detail for Monthly
     * */
    private boolean isValidMonthlyDetail() {
        try {
            if (this.isNull(this.recurrence)) {
                this.errorMsg = "Recurrence should not be empty at row %s.";
                return false;
            } else if (!this.frequencyDetailByTime.get(this.frequency).stream()
                .filter(x -> x.equals(Integer.valueOf(this.recurrence)))
                .findFirst().isPresent()) {
                this.errorMsg = String.format("Recurrence not valid its should be %s",
                this.frequencyDetailByTime.get(this.frequency)) + " at row %s.";
                return false;
            } else {
                return this.dateTimeValidation(false, true) ;
            }
        } catch (Exception ex) {
            this.errorMsg = "Issue with (Start Date,End Date,Start Time, Recurrence) at row %s.";
            return false;
        }
    }

    /**
     * Check the filed detail valid or not
     * @param filed
     * @return boolean true|false
     * */
    private static boolean isNull(String filed) {
        return (filed == null || filed.length() == 0) ? true : false;
    }

    /**
     * Validation the trigger detail
     * @return boolean true|false
     * */
    private boolean isValidTriggerDetail() {
        return !this.triggerDetails.contains(this.triggerDetail) ? true : false;
    }

    /**
     * Validation the frequency
     * @return boolean true|false
     * */
    private boolean isValidFrequency() {
        return !this.frequencyDetail.contains(this.frequency) ? true : false;
    }

    /**
     * check is this valid date with the give time zone
     * @param inputDate
     * @return boolean true|false
     * */
    private boolean isValidPattern(String inputDate, String dataFormat) {
        try {
            Pattern pattern = Pattern.compile(dataFormat);
            Matcher matcher = pattern.matcher(inputDate);
            return matcher.find() ? false : true;
        } catch (Exception ex) {
            return true;
        }
    }

    /**
     * This dateTimeValidation use to validate the date
     * @param isMonthlyCheck param for check the weekday
     * @param isWeekdayCheck param for check the monthly target date
     * @return boolean true|false
     * */
    private boolean dateTimeValidation(boolean isWeekdayCheck, boolean isMonthlyCheck) {
        // Check Start Date,End Date,Start Time
        // 1st check the start-date its should not be the yesterday date
        LocalDate userInputStartDate = LocalDate.parse(this.startDate);
        logger.info("User StartDate Valid " + userInputStartDate);
        // check the current date with the given time with zone
        LocalDate todayDateWithTimeZone = LocalDate.now();;
        logger.info("System Date With Time Zone " + todayDateWithTimeZone);
        // 2021-03-13 == 2021-03-13 || 2021-03-13 > 2021-03-12
        if (userInputStartDate.isEqual(todayDateWithTimeZone) || userInputStartDate.isAfter(todayDateWithTimeZone)) {
            // 2nd check the end-date its should not be the yesterday
            if (!isNull(this.endDate)) {
                LocalDate userInputEndDate = LocalDate.parse(this.endDate);
                logger.info("User End Date Valid " + userInputEndDate);
                // 2021-03-13 != 2021-03-13 || 2021-03-13 < 2021-03-15
                if (userInputEndDate.isBefore(userInputStartDate)) {
                    this.errorMsg = "EndDate should not be previous date at row %s.";
                    return false;
                }
            }
            String timeSplit[] = this.startTime.split(this.split);
            if (LocalDateTime.now().isAfter(userInputStartDate.atStartOfDay().plusHours(Integer.valueOf(timeSplit[0]))
                .plusMinutes(Integer.valueOf(timeSplit[1])))) {
                this.errorMsg = "StartTime should not be previous time at row %s.";
                return false;
            }
            return true;
        } else {
            this.errorMsg = "StartDate should not be previous date at row %s.";
            return false;
        }
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
