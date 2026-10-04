package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

/** Real Chrome and Edge M4 source-change journeys; invoked explicitly for milestone evidence. */
class M4BrowserSmokeIT {
    @Test void reviewApplyAndRevertInSupportedBrowsers(@TempDir Path temp) throws Exception {
        for (String browser : new String[]{"chrome", "msedge"}) run(browser, temp.resolve(browser));
    }

    private static void run(String browser, Path temp) throws Exception {
        Path root=temp.resolve("M4 project 漢字");Files.createDirectories(root.resolve("src/main/java/sample"));
        Files.writeString(root.resolve("pom.xml"),"<project><properties><maven.compiler.release>17</maven.compiler.release></properties><dependencies><dependency><groupId>org.jworkflow</groupId><artifactId>jworkflow-core</artifactId><version>0.1.0-SNAPSHOT</version></dependency></dependencies></project>");
        Files.writeString(root.resolve("src/main/java/sample/OrderEvent.java"),"package sample; public record OrderEvent(String id) {}");
        Files.writeString(root.resolve("src/main/java/sample/OrderCommand.java"),"package sample; import org.jworkflow.application.Command; public record OrderCommand(String id) implements Command {}");
        Files.writeString(root.resolve("src/main/java/sample/OrderListener.java"),"package sample; public final class OrderListener {}");
        try(var app=SpringApplication.run(WorkbenchApplication.class,"--workbench.browser.enabled=false","--workbench.preference-file="+temp.resolve("last.txt"),"--spring.ai.openai.api-key=")){
            int port=((WebServerApplicationContext)app).getWebServer().getPort();Path log=temp.resolve("m4-"+browser+".log");
            var process=new ProcessBuilder("node","scripts/m4-browser-smoke.mjs").redirectErrorStream(true).redirectOutput(log.toFile());
            process.environment().put("WORKBENCH_TEST_URL","http://127.0.0.1:"+port+"/#"+app.getBean(SessionAuthority.class).bootstrapToken());process.environment().put("WORKBENCH_PROJECT_ROOT",root.toString());process.environment().put("WORKBENCH_BROWSER",browser);
            Process child=process.start();boolean finished=child.waitFor(70,TimeUnit.SECONDS);if(!finished)child.destroyForcibly();String output=Files.readString(log);System.out.println(output);assertTrue(finished,"M4 "+browser+" journey timed out");assertEquals(0,child.exitValue(),output);
        }
    }
}
