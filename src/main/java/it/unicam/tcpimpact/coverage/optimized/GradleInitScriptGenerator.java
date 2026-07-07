package it.unicam.tcpimpact.coverage.optimized;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


public class GradleInitScriptGenerator {

    public Path generate(Path workDir, Path jacocoAgentJar, Path extensionJar) throws IOException {
        Files.createDirectories(workDir);
        Path initScript = workDir.resolve("tcpimpact-init.gradle");

        String script = """
                import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
 
                allprojects { project ->
                    project.plugins.withId('java') {
                        project.dependencies {
                            testRuntimeOnly project.files("%1$s", "%2$s")
                        }
                        project.tasks.withType(Test).configureEach {
                            systemProperty 'junit.jupiter.extensions.autodetection.enabled', 'true'
                            systemProperty 'tcpimpact.execOutputDir', System.getProperty('tcpimpact.execOutputDir')
                        }
                    }
 
                    // Project already has its own JaCoCo agent -> Disable Output
                    project.plugins.withId('jacoco') {
                        project.tasks.withType(Test).configureEach {
                            extensions.configure(JacocoTaskExtension) {
                                output = JacocoTaskExtension.Output.NONE
                            }
                        }
                    }
 
                    // No JaCoCo plugin in this Project -> Inject
                    project.afterEvaluate {
                        if (project.plugins.hasPlugin('java') && !project.plugins.hasPlugin('jacoco')) {
                            project.tasks.withType(Test).configureEach {
                                jvmArgs "-javaagent:%2$s=output=none"
                            }
                        }
                    }
                }
                """.formatted(
                toGradlePath(extensionJar),
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