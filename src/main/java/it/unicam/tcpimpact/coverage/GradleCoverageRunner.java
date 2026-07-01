package it.unicam.tcpimpact.coverage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class GradleCoverageRunner {

    /**
     * Runs a single test or test class with Gradle and generates a JaCoCo XML report.
     *
     * @param repoPath root path of the Git repository
     * @param projectPath path of the Gradle project inside the repository
     * @param testName Gradle test filter
     * @return path to the generated JaCoCo XML report
     * @throws IOException if the process cannot be started or the report is missing
     * @throws InterruptedException if the Gradle process is interrupted
     */
    public Path runCoverage(Path repoPath, Path projectPath, String testName)
            throws IOException, InterruptedException {

        if (repoPath == null || projectPath == null)
            throw new IllegalArgumentException("Paths must not be null");

        Path gradleProjectPath = repoPath.resolve(projectPath).toAbsolutePath().normalize();
        Path gradleWrapper = gradleProjectPath.resolve(resolveGradleWrapperName());

        if(!Files.exists(gradleWrapper))
            throw new IllegalArgumentException("Gradle wrapper not found: " + gradleWrapper);

        List<String> command = buildCommand(gradleWrapper, testName);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gradleProjectPath.toFile());
        pb.inheritIO();

        Process process = pb.start();
        int exitCode = process.waitFor();

        if(exitCode != 0)
            throw new IOException("Gradle coverage execution failed with exit code: " + exitCode);

        Path jacocoReportPath = gradleProjectPath.resolve("build/reports/jacoco/test/jacocoTestReport.xml");

        if(!Files.exists(jacocoReportPath))
            throw new IOException("Jacoco report not found: " + jacocoReportPath);
        return jacocoReportPath;
    }

    private List<String> buildCommand(Path gradleWrapper, String testName) {
        List<String> command = new ArrayList<>();
        command.add(gradleWrapper.toString());
        command.add("test");

        if(testName != null && !testName.isBlank()){
            command.add("--tests");
            command.add(testName);
        }

        command.add("jacocoTestReport");
        return command;
    }

    private String resolveGradleWrapperName(){
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        return isWindows ? "gradlew.bat" : "gradlew";
    }
}
