package it.unicam.tcpimpact.coverage.optimized;

import it.unicam.tcpimpact.coverage.TestCaseId;
import it.unicam.tcpimpact.coverage.TestDiscoverer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class PerTestCoverageRunner {

    private final TestDiscoverer discoverer;
    private final GradleCoverageRunner runner;
    private final GradleInitScriptGenerator initScriptGenerator;
    private final JacocoReportGenerator reportGenerator;

    public PerTestCoverageRunner() {
        this.discoverer = new TestDiscoverer();
        this.runner = new GradleCoverageRunner();
        this.initScriptGenerator = new GradleInitScriptGenerator();
        this.reportGenerator = new JacocoReportGenerator();
    }

    public Map<TestCaseId, Path> runCoverageForAllTests(Path repoPath, Path projectPath)
            throws IOException, InterruptedException {

        Path gradlePath = repoPath.resolve(projectPath).toAbsolutePath().normalize();
        List<TestCaseId> testCases = discoverer.discoverTests(gradlePath);

        Path tcpImpactDir = gradlePath.resolve(".tcpimpact").resolve("per-test-coverage").toAbsolutePath().normalize();
        Path execDir = tcpImpactDir.resolve("exec");
        Path xmlDir = tcpImpactDir.resolve("xml");
        Path bootstrapDir = tcpImpactDir.resolve("bootstrap");
        Files.createDirectories(execDir);
        Files.createDirectories(xmlDir);
        Files.createDirectories(bootstrapDir);

        CoverageAgentResources agentResources = new CoverageAgentResources(bootstrapDir);
        Path jacocoAgentJar = agentResources.extractJacocoAgentJar();
        Path extensionJar = agentResources.extractExtensionJar();
        Path initScript = initScriptGenerator.generate(bootstrapDir, jacocoAgentJar, extensionJar);

        System.out.println("\nRunning the full test suite");
        runner.runSuiteWithPerTestCoverage(repoPath, projectPath, initScript, execDir);

        Path compiledClassesDir = gradlePath.resolve("build/classes/java/main");

        Map<TestCaseId, Path> reports = new LinkedHashMap<>();
        for (TestCaseId testCase : testCases) {
            Path execFile = execDir.resolve(testCase.toSafeFileName() + ".exec");
            if (!Files.exists(execFile)) {
                System.err.println("Warning: no coverage data found for " + testCase
                        + " (it may not have run, or produced no coverage).");
                continue;
            }
            Path xmlReport = xmlDir.resolve(testCase.toSafeFileName() + ".xml");
            reportGenerator.generateXmlReport(execFile, compiledClassesDir, xmlReport, testCase.toString());
            reports.put(testCase, xmlReport);
        }

        return reports;
    }
}
