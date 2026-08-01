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

    // VelocityEngine itself is documented as thread-safe once initialized, so it's fine to
    // share across concurrent callers -- unlike the per-request context/writer that used to
    // live here as instance fields (see below).
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

    /**
     * Renders a template into a response string. This class is a singleton bean, and this
     * method is invoked concurrently from multiple threads (Kafka callback threads, parallel
     * job-scheduling streams, concurrent REST notify calls) -- context/writer are kept as
     * local variables (not instance fields) so two concurrent renders can never see or
     * overwrite each other's in-flight state, which previously could send one job's email
     * with another job's content.
     * */
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
        throw new NullPointerException("Template Not Found");
    }

    private VelocityEngine getEngine() {
        return new VelocityEngine();
    }

}
