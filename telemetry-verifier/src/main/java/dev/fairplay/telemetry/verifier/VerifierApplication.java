package dev.fairplay.telemetry.verifier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/** A one-shot command-line app: it runs {@link VerifierRunner}, then exits with the runner's status code. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VerifierApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(VerifierApplication.class, args);
        // SpringApplication.exit asks every ExitCodeGenerator bean, and VerifierRunner is one.
        System.exit(SpringApplication.exit(context));
    }
}
