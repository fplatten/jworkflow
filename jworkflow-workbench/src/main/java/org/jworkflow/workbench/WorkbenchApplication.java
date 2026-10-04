package org.jworkflow.workbench;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Local web application started with {@code java -jar}; no desktop terminal runner is installed. */
@SpringBootApplication
@EnableScheduling
public class WorkbenchApplication {
    static final String USAGE = "Usage: java -jar jworkflow-workbench.jar [projectDirectory] [--spring.option=value ...]";

    public static void main(String[] args) {
        SpringApplication.run(WorkbenchApplication.class, launchArguments(args));
    }

    /** One optional positional project directory becomes a root suggestion only; nothing is read until confirmation. */
    static String[] launchArguments(String[] args) {
        List<String> result = new ArrayList<>();
        String project = null;
        for (String arg : args) {
            if (arg.startsWith("--")) result.add(arg);
            else if (project == null && !arg.isBlank()) project = arg;
            else throw new IllegalArgumentException(USAGE);
        }
        if (project != null) result.add("--workbench.project=" + Path.of(project).toAbsolutePath().normalize());
        return result.toArray(String[]::new);
    }
}
