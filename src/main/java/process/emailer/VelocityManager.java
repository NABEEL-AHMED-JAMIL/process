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

@Component
public class VelocityManager {

    private Logger logger = LogManager.getLogger(VelocityManager.class);

    private VelocityEngine engine;

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
        VelocityContext context = new VelocityContext();
        context.put("request", object);
        StringWriter writer = new StringWriter();
        return this.getWriterResponse(templateType, context, writer).toString();
    }

    private StringWriter getWriterResponse(TemplateType templateType, VelocityContext context, StringWriter writer) throws NullPointerException {
        Template template = this.engine.getTemplate(templateType.getTemplatePath());
        if (template != null) {
            template.merge(context, writer);
            return writer;
        }
        throw new NullPointerException("Template not found.");
    }

    private VelocityEngine getEngine() {
        return new VelocityEngine();
    }

}
