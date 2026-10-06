package process.metering;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.barco.platform.meter.MeterReporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

@Configuration
public class MeterReporterConfig {

    /**
     * The usage ledger (etl_meter), through platform-commons' one reporter (MIG-80). Not configured
     * means every report is a no-op. Usage the meter cannot take now waits in the spool, and what it
     * refuses is parked under its dead/ -- a volume, so neither is lost with the container.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    public MeterReporter meterReporter(@Value("${meter.url:}") String url, @Value("${meter.service-key:}") String serviceKey,
        @Value("${meter.spool-dir:${java.io.tmpdir}/process-meter-spool}") String spoolDir, MeterRegistry registry) {
        MeterReporter reporter = new MeterReporter(url, serviceKey, Paths.get(spoolDir));
        // One series per outcome (usage_events_total{outcome="rejected"} ...): a rejected or parked
        // count above zero is usage someone has to look at.
        reporter.counts().asMap().keySet().forEach(outcome -> FunctionCounter
            .builder("usage.events", reporter, r -> r.counts().asMap().get(outcome))
            .tag("outcome", outcome).description("Usage events reported to the meter, by what became of them")
            .register(registry));
        return reporter;
    }
}
