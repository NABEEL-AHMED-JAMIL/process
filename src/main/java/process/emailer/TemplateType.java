package process.emailer;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum TemplateType {

    COMPLETE_JOB("COMPLETE_JOB", "templates/complete_job.vm"),
    FAIL_JOB("FAIL_JOB", "templates/fail_job.vm"),
    SKIP_JOB("SKIP_JOB", "templates/skip_job.vm"),
    REGISTER_USER("REGISTER_USER", "templates/app-user-register.vm"),
    FORGOT_PASS("REGISTER_USER", "templates/app-user-forgotpass.vm"),
    RESET_PASS("REGISTER_USER", "templates/app-user-reset-password.vm"),
    UPDATE_ACCOUNT_PROFILE("UPDATE_ACCOUNT_PROFILE", "templates/update_account_profile.vm"),
    CLOSE_ACCOUNT("CLOSE_ACCOUNT", "templates/close_account.vm"),
    CHANGE_ACCOUNT_TIMEZONE("CHANGE_ACCOUNT_TIMEZONE", "templates/change_account_timezone.vm");

    private String templateName;
    private String templatePath;

    TemplateType(String templateName, String templatePath) {
        this.templateName = templateName;
        this.templatePath = templatePath;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public String getTemplatePath() {
        return templatePath;
    }

    public void setTemplatePath(String templatePath) {
        this.templatePath = templatePath;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
