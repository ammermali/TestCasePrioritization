package it.unicam.tcpimpact.coverage.optimized;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


public class GradleInitScriptGenerator {

    public Path generate(Path workDir, Path jacocoAgentJar, Path extensionJar) throws IOException {
        Files.createDirectories(workDir);
        Path initScript = workDir.resolve("tcpimpact-init.gradle");

        String script = """
                allprojects {
                    plugins.withId('java') {
                        dependencies {
                            testRuntimeOnly files("%s", "%s")
                        }
                        tasks.withType(Test).configureEach {
                            jvmArgs "-javaagent:%s=output=none"
                            systemProperty 'junit.jupiter.extensions.autodetection.enabled', 'true'
                            systemProperty 'tcpimpact.execOutputDir', System.getProperty('tcpimpact.execOutputDir')
                        }
                    }
                }
                """.formatted(
                toGradlePath(extensionJar),
                toGradlePath(jacocoAgentJar),
                toGradlePath(jacocoAgentJar)
        );

        Files.writeString(initScript, script);
        return initScript;
    }

    private String toGradlePath(Path path) {
        // Groovy string literals in Gradle scripts use forward slashes even on Windows.
        return path.toAbsolutePath().normalize().toString().replace("\\", "/");
    }
}