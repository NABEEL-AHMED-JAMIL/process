package process.emailer;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum TemplateType {

    COMPLETE_JOB("TEST_TEMPLATE", "templates/complete_job.vm"),
    FAIL_JOB("TEST_TEMPLATE", "templates/fail_job.vm"),
    SKIP_JOB("SKIP_JOB", "templates/skip_job.vm");

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
