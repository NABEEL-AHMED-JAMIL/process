package process.util;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class EnvPrinter implements CommandLineRunner {

    private final Environment env;

    public EnvPrinter(Environment env) {
        this.env = env;
    }

    @Override
    public void run(String... args) {
        System.out.println("DB URL: " + env.getProperty("spring.datasource.url"));
        System.out.println("DB User: " + env.getProperty("spring.datasource.username"));
    }
}