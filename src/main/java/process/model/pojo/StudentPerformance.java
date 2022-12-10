package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "student_performance")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StudentPerformance {

    @GenericGenerator(
        name = "studentPerformanceSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "student_id_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name="source_task_type_id", unique=true, nullable=false)
    @GeneratedValue(generator = "studentPerformanceSequenceGenerator")
    private Long studentId;

    @Column(name = "school")
    private String school;

    @Column(name = "sex")
    private String sex;

    @Column(name = "age")
    private Integer age;

    @Column(name = "address")
    private String address;

    @Column(name = "fAmsize")
    private String fAmsize;

    @Column(name = "pStatus")
    private String pStatus;

    @Column(name = "medu")
    private Integer medu;

    @Column(name = "fedu")
    private Integer fedu;

    @Column(name = "mJob")
    private String mJob;

    @Column(name = "fJob")
    private String fJob;

    @Column(name = "reason")
    private String reason;

    @Column(name = "guardian")
    private String guardian;

    @Column(name = "travel_time")
    private Integer travelTime;

    @Column(name = "study_time")
    private Integer studyTime;

    @Column(name = "failures")
    private Integer failures;

    @Column(name = "school_sup")
    private String schoolSup;

    @Column(name = "fam_sup")
    private String famSup;

    @Column(name = "paid")
    private String paid;

    @Column(name = "activities")
    private String activities;

    @Column(name = "nursery")
    private String nursery;

    @Column(name = "higher")
    private String higher;

    @Column(name = "internet")
    private String internet;

    @Column(name = "romantic")
    private String romantic;

    @Column(name = "famrel")
    private Integer famrel;

    @Column(name = "freeTime")
    private Integer freeTime;

    @Column(name = "goout")
    private Integer goout;

    @Column(name = "dacl")
    private Integer dacl;

    @Column(name = "wacl")
    private Integer wacl;

    @Column(name = "health")
    private Integer health;

    @Column(name = "absences")
    private Integer absences;

    @Column(name = "g1")
    private Integer g1;

    @Column(name = "g2")
    private Integer g2;

    @Column(name = "g3")
    private Integer g3;

    public StudentPerformance() {}

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public String getSchool() {
        return school;
    }

    public void setSchool(String school) {
        this.school = school;
    }

    public String getSex() {
        return sex;
    }

    public void setSex(String sex) {
        this.sex = sex;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getfAmsize() {
        return fAmsize;
    }

    public void setfAmsize(String fAmsize) {
        this.fAmsize = fAmsize;
    }

    public String getpStatus() {
        return pStatus;
    }

    public void setpStatus(String pStatus) {
        this.pStatus = pStatus;
    }

    public Integer getMedu() {
        return medu;
    }

    public void setMedu(Integer medu) {
        this.medu = medu;
    }

    public Integer getFedu() {
        return fedu;
    }

    public void setFedu(Integer fedu) {
        this.fedu = fedu;
    }

    public String getmJob() {
        return mJob;
    }

    public void setmJob(String mJob) {
        this.mJob = mJob;
    }

    public String getfJob() {
        return fJob;
    }

    public void setfJob(String fJob) {
        this.fJob = fJob;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getGuardian() {
        return guardian;
    }

    public void setGuardian(String guardian) {
        this.guardian = guardian;
    }

    public Integer getTravelTime() {
        return travelTime;
    }

    public void setTravelTime(Integer travelTime) {
        this.travelTime = travelTime;
    }

    public Integer getStudyTime() {
        return studyTime;
    }

    public void setStudyTime(Integer studyTime) {
        this.studyTime = studyTime;
    }

    public Integer getFailures() {
        return failures;
    }

    public void setFailures(Integer failures) {
        this.failures = failures;
    }

    public String getSchoolSup() {
        return schoolSup;
    }

    public void setSchoolSup(String schoolSup) {
        this.schoolSup = schoolSup;
    }

    public String getFamSup() {
        return famSup;
    }

    public void setFamSup(String famSup) {
        this.famSup = famSup;
    }

    public String getPaid() {
        return paid;
    }

    public void setPaid(String paid) {
        this.paid = paid;
    }

    public String getActivities() {
        return activities;
    }

    public void setActivities(String activities) {
        this.activities = activities;
    }

    public String getNursery() {
        return nursery;
    }

    public void setNursery(String nursery) {
        this.nursery = nursery;
    }

    public String getHigher() {
        return higher;
    }

    public void setHigher(String higher) {
        this.higher = higher;
    }

    public String getInternet() {
        return internet;
    }

    public void setInternet(String internet) {
        this.internet = internet;
    }

    public String getRomantic() {
        return romantic;
    }

    public void setRomantic(String romantic) {
        this.romantic = romantic;
    }

    public Integer getFamrel() {
        return famrel;
    }

    public void setFamrel(Integer famrel) {
        this.famrel = famrel;
    }

    public Integer getFreeTime() {
        return freeTime;
    }

    public void setFreeTime(Integer freeTime) {
        this.freeTime = freeTime;
    }

    public Integer getGoout() {
        return goout;
    }

    public void setGoout(Integer goout) {
        this.goout = goout;
    }

    public Integer getDacl() {
        return dacl;
    }

    public void setDacl(Integer dacl) {
        this.dacl = dacl;
    }

    public Integer getWacl() {
        return wacl;
    }

    public void setWacl(Integer wacl) {
        this.wacl = wacl;
    }

    public Integer getHealth() {
        return health;
    }

    public void setHealth(Integer health) {
        this.health = health;
    }

    public Integer getAbsences() {
        return absences;
    }

    public void setAbsences(Integer absences) {
        this.absences = absences;
    }

    public Integer getG1() {
        return g1;
    }

    public void setG1(Integer g1) {
        this.g1 = g1;
    }

    public Integer getG2() {
        return g2;
    }

    public void setG2(Integer g2) {
        this.g2 = g2;
    }

    public Integer getG3() {
        return g3;
    }

    public void setG3(Integer g3) {
        this.g3 = g3;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
