package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import process.model.enums.Frequency;
import process.util.ProcessTimeUtil;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static java.time.temporal.ChronoUnit.DAYS;

/**
 * This JobDetailValidation validate the information of the sheet
 * if the date not valid its stop the process and through the valid msg
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobDetailValidation {

    private Logger logger = LoggerFactory.getLogger(JobDetailValidation.class);

    private Integer rowCounter = 0;

    // detail for advance validation
    private List<String> frequencyDetail = ProcessTimeUtil.frequency;
    private List<String> priorityDetail = ProcessTimeUtil.priority;
    private List<String> checkedDetail = ProcessTimeUtil.checked;
    private Map<String, List<?>> frequencyDetailByTime = ProcessTimeUtil.frequencyDetail;
    // use to split the time
    private String split = ":";
    // time format validation
    private String timeFormat = "^(0[0-9]|1[0-9]|2[0-3]):([0-5][0-9])$";
    // date format validation
    private String dateFormat = "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[1-2][0-9]|3[0-1])$";

    private String jobName;
    private String taskId;
    private String startDate;
    private String endDate;
    private String startTime;
    private String frequency;
    private String recurrence;
    private String priority;
    private String emailJobComplete;
    private String emailJobFail;
    private String emailJobSkip;
    private String errorMsg;

    public JobDetailValidation() { }

    public Integer getRowCounter() {
        return rowCounter;
    }

    public void setRowCounter(Integer rowCounter) {
        this.rowCounter = rowCounter;
    }

    public String getJobName() {
        return jobName;
    }
    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getTaskId() {
        return taskId;
    }
    public void setTaskId(String taskId) {
        this.taskId = taskId;
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

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getEmailJobComplete() {
        return emailJobComplete;
    }

    public void setEmailJobComplete(String emailJobComplete) {
        this.emailJobComplete = emailJobComplete;
    }

    public String getEmailJobFail() {
        return emailJobFail;
    }

    public void setEmailJobFail(String emailJobFail) {
        this.emailJobFail = emailJobFail;
    }

    public String getEmailJobSkip() {
        return emailJobSkip;
    }

    public void setEmailJobSkip(String emailJobSkip) {
        this.emailJobSkip = emailJobSkip;
    }

    public String getErrorMsg() {
        return errorMsg;
    }
    public void setErrorMsg(String errorMsg) {
        if (isNull(this.errorMsg)) {
            this.errorMsg = errorMsg;
        } else {
            this.errorMsg += errorMsg;
        }
    }

    /**
     * This isValidJobDetail use to validate the
     * job detail of the job valid return true
     * if non-valid return false
     * @return boolean true|false
     * */
    public void isValidJobDetail() {
        if (this.isNull(this.jobName)) {
            this.setErrorMsg(String.format("JobName should not be empty at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.taskId)) {
            this.setErrorMsg(String.format("TaskId should not be empty at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.startDate)) {
            this.setErrorMsg(String.format("StartDate should not be empty at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.startTime)) {
            this.setErrorMsg(String.format("StartTime should not be empty at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.frequency)) {
            this.setErrorMsg(String.format("Frequency should not be empty at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.priority)) {
            this.setErrorMsg(String.format("Priority should not be empty at row %s.<br>", rowCounter));
        }
        if (this.isValidPattern(this.startDate, this.dateFormat)) {
            this.setErrorMsg(String.format("Invalid StartDate at row %s.<br>", rowCounter));
        }
        if (this.isValidPattern(this.startTime, this.timeFormat)) {
            this.setErrorMsg(String.format("Invalid StartTime at row %s.<br>", rowCounter));
        }
        if (this.isValidFrequency()) {
            this.setErrorMsg(String.format("Frequency not valid its should be %s at row %s.", this.frequencyDetail.toString(), rowCounter));
        }
        if (!this.isNull(this.endDate) && this.isValidPattern(this.endDate, this.dateFormat)) {
            this.setErrorMsg(String.format("Invalid EndDate at row %s.<br>", rowCounter));
        }
        if (this.isValidPriority()) {
            this.setErrorMsg(String.format("Priority not valid its should be %s at row %s.<br>", this.priorityDetail.toString(), rowCounter));
        }
        if (!this.isNull(this.emailJobComplete)  && this.isValidChecked(this.emailJobComplete)) {
            this.setErrorMsg(String.format("EmailJobComplete not valid its should be %s at row %s.<br>", this.checkedDetail.toString(), rowCounter));
        }
        if (!this.isNull(this.emailJobFail)  && this.isValidChecked(this.emailJobFail)) {
            this.setErrorMsg(String.format("EmailJobFail Complete not valid its should be %s at row %s.<br>", this.checkedDetail.toString(), rowCounter));
        }
        if (!this.isNull(this.emailJobSkip)  && this.isValidChecked(this.emailJobSkip)) {
            this.setErrorMsg(String.format("EmailJobSkip Complete not valid its should be %s at row %s.<br>", this.checkedDetail.toString(), rowCounter));
        }
        this.isValidDetail();
    }

    /**
     * This isValidDetail validate detail for check the date time valid or not
     * if the detail are valid then its return true if not then false
     * @return void
     * */
    private void isValidDetail() {
        try {
            if (!this.isNull(this.recurrence) && !this.frequencyDetailByTime.get(this.frequency)
                .stream().filter(x -> x.equals(Integer.valueOf(this.recurrence))).findFirst().isPresent()) {
                this.setErrorMsg(String.format("Recurrence not valid its should be %s at row %s.",
                    this.frequencyDetailByTime.get(this.frequency), rowCounter));
            }
            if (this.frequency.equals(Frequency.Mint.name()) || this.frequency.equals(Frequency.Hr.name())
                || this.frequency.equals(Frequency.Daily.name())) {
                this.dateTimeValidation(false, false);
            } else if (this.frequency.equals(Frequency.Weekly.name())) {
                this.dateTimeValidation(true, false);
            } else if (this.frequency.equals(Frequency.Monthly.name())) {
                this.dateTimeValidation(false, true) ;
            }
        } catch (Exception ex) {
            this.setErrorMsg(String.format("Issue with (Start Date,End Date,Start Time,Recurrence) at row %s.<br>", rowCounter));
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
     * Validation the frequency
     * @return boolean true|false
     * */
    private boolean isValidFrequency() {
        return !this.frequencyDetail.contains(this.frequency) ? true : false;
    }

    /**
     * Validation the priority
     * @return boolean true|false
     * */
    private boolean isValidPriority() {
        return !this.priorityDetail.contains(this.priority) ? true : false;
    }

    /**
     * Validation the checked
     * @param checkDetail
     * @return boolean true|false
     * */
    private boolean isValidChecked(String checkDetail) {
        return !this.checkedDetail.contains(StringUtils.capitalize(checkDetail.toLowerCase())) ? true : false;
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
     * @return void
     * */
    private void dateTimeValidation(boolean isWeekdayCheck, boolean isMonthlyCheck) {
        // Check Start Date,End Date,Start Time
        // 1st check the start-date it's should not be the yesterday date
        LocalDate userInputStartDate = LocalDate.parse(this.startDate);
        logger.info("User StartDate Valid " + userInputStartDate);
        // check the current date with the given time with zone
        LocalDate todayDateWithTimeZone = LocalDate.now();;
        logger.info("System Date With Time Zone " + todayDateWithTimeZone);
        // 2021-03-13 == 2021-03-13 || 2021-03-13 > 2021-03-12
        if (userInputStartDate.isEqual(todayDateWithTimeZone) || userInputStartDate.isAfter(todayDateWithTimeZone)) {
            // 2nd check the end-date it's should not be the yesterday
            if (!isNull(this.endDate)) {
                LocalDate userInputEndDate = LocalDate.parse(this.endDate);
                logger.info("User End Date Valid " + userInputEndDate);
                // 2021-03-13 != 2021-03-13 || 2021-03-13 < 2021-03-15
                if (userInputEndDate.isBefore(userInputStartDate)) {
                    this.setErrorMsg(String.format("EndDate should not be previous date at row %s.<br>", rowCounter));
                } else if ((DAYS.between(userInputStartDate, userInputEndDate) < 6) && isWeekdayCheck) {
                    this.setErrorMsg(String.format("EndDate must be 7 day difference from StartDate at row %s.<br>", rowCounter));
                } else if ((DAYS.between(userInputStartDate, userInputEndDate) < 30) && isMonthlyCheck) {
                    this.setErrorMsg(String.format("EndDate must be 31 day difference from StartDate at row %s.<br>", rowCounter));
                }
            }
            String timeSplit[] = this.startTime.split(this.split);
            if (LocalDateTime.now().isAfter(userInputStartDate.atStartOfDay()
                .plusHours(Integer.valueOf(timeSplit[0])).plusMinutes(Integer.valueOf(timeSplit[1])))) {
                this.setErrorMsg(String.format("StartTime should not be previous time at row %s.<br>", rowCounter));
            }
        } else {
            this.setErrorMsg(String.format("StartDate should not be previous date at row %s.", rowCounter));
        }
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
