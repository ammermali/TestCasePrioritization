package it.unicam.tcpimpact.coverage.optimized;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;


public class CoverageAgentResources {

    private static final String JACOCO_AGENT_RESOURCE = "/tcpimpact-agents/jacocoagent.jar";
    private static final String EXTENSION_JAR_RESOURCE = "/tcpimpact-agents/tcpimpact-junit-extension.jar";

    private final Path workDir;

    public CoverageAgentResources(Path workDir) {
        this.workDir = workDir;
    }

    public Path extractJacocoAgentJar() throws IOException {
        return extractResource(JACOCO_AGENT_RESOURCE, "jacocoagent.jar");
    }

    public Path extractExtensionJar() throws IOException {
        return extractResource(EXTENSION_JAR_RESOURCE, "tcpimpact-junit-extension.jar");
    }

    private Path extractResource(String resourcePath, String fileName) throws IOException {
        Files.createDirectories(workDir);
        Path destination = workDir.resolve(fileName);

        try (InputStream resourceStream = CoverageAgentResources.class.getResourceAsStream(resourcePath)) {
            if (resourceStream == null) {
                throw new IOException(
                        "Bundled resource not found on classpath: " + resourcePath
                                + ". Did you place the built jars under src/main/resources/tcpimpact-agents/?");
            }
            Files.copy(resourceStream, destination, StandardCopyOption.REPLACE_EXISTING);
        }

        return destination;
    }
}