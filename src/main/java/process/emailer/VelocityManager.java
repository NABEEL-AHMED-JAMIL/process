package process.emailer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import java.io.StringWriter;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 */
@Component
public class VelocityManager {

    private Logger logger = LogManager.getLogger(VelocityManager.class);

    private VelocityEngine engine;
    /*  create a context and add data */
    private VelocityContext context;
    /* now render the template into a StringWriter */
    private StringWriter writer;

    @PostConstruct
    public void init() {
        logger.info("+================Velocity-Start====================+");
        this.engine = getEngine();
        this.engine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
        this.engine.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
        this.engine.init();
        logger.info("+================Velocity-End====================+");
    }

    public VelocityManager() { }

    public String getResponseMessage(TemplateType templateType, Map<String, Object> object) {
        String responseMessage;
        this.setWriter(new StringWriter());
        this.setContext(new VelocityContext());
        //logger.info("Request Content :- " + object);
        this.context.put("request", object);
        responseMessage = this.getWriterResponse(templateType).toString();
        return responseMessage;
    }

    private StringWriter getWriterResponse(TemplateType templateType) throws NullPointerException {
        Template template = this.engine.getTemplate(templateType.getTemplatePath());
        if (template != null) {
            template.merge(this.getContext(), this.getWriter());
            //logger.info("Response Content :- " + this.getWriter().toString().replaceAll("\\s+",""));
            return this.getWriter();
        }
        throw new NullPointerException("Template Not Found");
    }
    private VelocityEngine getEngine() {
        return new VelocityEngine();
    }

    public VelocityContext getContext() {
        return context;
    }

    public void setContext(VelocityContext context) {
        this.context = context;
    }

    public StringWriter getWriter() {
        return writer;
    }
    public void setWriter(StringWriter writer) {
        this.writer = writer;
    }

}
