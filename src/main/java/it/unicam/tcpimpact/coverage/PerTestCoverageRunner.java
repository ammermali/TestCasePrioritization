package it.unicam.tcpimpact.coverage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PerTestCoverageRunner {

    private final TestDiscoverer discoverer;
    private final GradleCoverageRunner runner;

    public PerTestCoverageRunner() {
        this.discoverer = new TestDiscoverer();
        this.runner = new GradleCoverageRunner();
    }

    // TODO: optimize. Currently, it takes around 15 seconds for test case
    public Map<TestCaseId, Path> runCoverageForAllTests(Path repoPath, Path projectPath)
            throws IOException, InterruptedException {
        Path gradlePath = repoPath.resolve(projectPath).toAbsolutePath().normalize();
        List<TestCaseId> testCases = discoverer.discoverTests(gradlePath);
        Path outputDir = gradlePath.resolve(".tcpimpact").resolve("per-test-coverage").toAbsolutePath().normalize();
        Files.createDirectories(outputDir);
        Map<TestCaseId, Path> reports = new LinkedHashMap<>();
        for (TestCaseId testCase : testCases) {
            System.out.println("\nRunning coverage for test: " + testCase);
            Path jacocoReport = runner.runCoverage(repoPath, projectPath, testCase.toGradleFilter());
            Path copiedReport = outputDir.resolve(testCase.toSafeFileName() + ".xml");
            Files.copy(jacocoReport, copiedReport, StandardCopyOption.REPLACE_EXISTING);
            reports.put(testCase, copiedReport);
            Files.deleteIfExists(gradlePath.resolve("build/jacoco/test.exec"));
            Files.deleteIfExists(gradlePath.resolve("build/reports/jacoco/test/jacocoTestReport.xml"));
        }
        return reports;
    }
}
