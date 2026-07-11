package it.unicam.tcpimpact.cli;
import it.unicam.tcpimpact.coverage.optimized.PerTestCoverageRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import it.unicam.tcpimpact.coverage.TestCaseId;
import it.unicam.tcpimpact.coverage.TestDiscoverer;
import it.unicam.tcpimpact.coverage.optimized.JacocoReportGenerator;
import it.unicam.tcpimpact.git.GitRepositoryValidator;
import it.unicam.tcpimpact.graph.ImpactGraphBuilder;
import it.unicam.tcpimpact.graph.ParserFactory;
import it.unicam.tcpimpact.graph.model.ImpactGraph;
import it.unicam.tcpimpact.model.ChangedMethod;
import it.unicam.tcpimpact.model.MethodId;
import it.unicam.tcpimpact.model.MethodRange;
import it.unicam.tcpimpact.parser.ChangedMethodDetector;
import it.unicam.tcpimpact.parser.RevisionMethodExtractor;
import it.unicam.tcpimpact.priority.TestPriorityRanker;
import it.unicam.tcpimpact.risk.RiskPropagator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import it.unicam.tcpimpact.git.ChangedJavaFile;
import it.unicam.tcpimpact.git.DiffExtractor;


/**
 * Main command of the TCP Impact prototype.
 * This command receives the path of a Git repository and two Git revisions
 * representing the range of changes to analyze, then prints the Java methods
 * affected by those changes.
 */

@Command(
        name = "tcp-impact",
        mixinStandardHelpOptions = true,
        version = "tcp-impact 0.1.0",
        description = "Runs TCP Impact prioritization for a Git diff and writes a ranked test list."
)



public class TcpImpactCommand implements Callable<Integer> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Path to the repo that will be analyzed
    @Option(
            names = "--repo", // local repo path
            required = true,
            description = "Path to the Git repository to analyze."
    )
    private Path repoPath;

    // Base Git revision used as the starting point of the analysis
    @Option(
            names = "--base",
            defaultValue = "HEAD~1",
            description = "Base Git revision. Default: HEAD~1"
    )
    private String baseRevision;

    // Head Git revision used as the ending point of the analysis
    @Option(
            names = "--head",
            defaultValue = "HEAD",
            description = "Head Git revision. Default: HEAD"
    )
    private String headRevision;

    // the project path might differ from the repo path
    @Option(
            names = "--project-path",
            defaultValue = ".",
            description = "Path of the Java project inside the Git repository."
    )
    private Path projectPath;

    @Option(
            names = "--output",
            description = "Priority ranking JSON output path. Relative paths are resolved inside the analyzed project."
    )
    private Path output;

    @Option(
            names = "--graph-output",
            description = "Optional impact graph JSON output path. Relative paths are resolved inside the analyzed project."
    )
    private Path graphOutput;

    @Option(
            names = "--coverage-dir",
            description = "Optional directory containing JaCoCo XML coverage reports."
    )
    private Path coverageDir;

    @Option(
            names = "--source-classes-dir",
            description = "Production Java source root relative to --project-path. Useful for non-standard layouts."
    )
    private Path sourceClassesDir;

    @Option(
            names = "--source-tests-dir",
            description = "Test Java source root relative to --project-path. Useful for non-standard layouts."
    )
    private Path sourceTestsDir;

    @Option(
            names = "--max-depth",
            defaultValue = "4",
            description = "Maximum propagation depth for priority ranking."
    )
    private int maxDepth;

    @Option(
            names = "--coverage-all-tests",
            hidden = true,
            description = "Internal legacy Gradle coverage mode."
    )
    private boolean coverageAllTests;

    @Option(
            names = "--impact-graph-output",
            hidden = true,
            description = "Internal legacy graph output path inside .tcpimpact."
    )
    private Path impactGraphOutput;

    @Option(
            names = "--priority-ranking-output",
            hidden = true,
            description = "Internal legacy ranking output path inside .tcpimpact."
    )
    private Path priorityRankingOutput;

    @Option(
            names = "--discover-tests-output",
            hidden = true,
            description = "Writes discovered JUnit test methods as JSON inside the analyzed project's .tcpimpact directory."
    )
    private Path discoverTestsOutput;

    @Option(
            names = "--jacoco-exec-dir",
            hidden = true,
            description = "Directory containing per-test JaCoCo .exec files to convert to XML."
    )
    private Path jacocoExecDir;

    @Option(
            names = "--jacoco-report-map",
            hidden = true,
            description = "JSON object mapping .exec file names to JaCoCo report names."
    )
    private Path jacocoReportMap;

    @Option(
            names = "--jacoco-classes-dir",
            hidden = true,
            description = "Compiled production classes directory used when converting JaCoCo .exec files."
    )
    private Path jacocoClassesDir;

    @Option(
            names = "--jacoco-xml-output-dir",
            hidden = true,
            description = "Directory where converted JaCoCo XML reports are written."
    )
    private Path jacocoXmlOutputDir;

    /**
     * Executes the command.
     * The method prints the received configuration, validates the repository
     * path, detects changed Java methods, and returns an exit code compatible
     * with command-line execution.
     * Note: at the moment, this method may raise an error in the terminal.
     * Ignore it, as it is related to the JGit logger and isn't influential
     * in any way for the prototype.
     *
     * @return 0 if the analysis succeeds, 1 otherwise (CLI exit code)
     */
    @Override
    public Integer call() throws Exception {
        System.out.println("TCP Impact prototype");
        System.out.println("Repo: " + repoPath.toAbsolutePath());
        System.out.println("Base: " + baseRevision);
        System.out.println("Head: " + headRevision);

        GitRepositoryValidator validator = new GitRepositoryValidator();

        if(!validator.isGitRepository(repoPath)){
            System.out.println("Invalid Git repository");
            return 1;
        }

        System.out.println("Git repository validated");

        if(jacocoExecDir != null || jacocoReportMap != null || jacocoClassesDir != null || jacocoXmlOutputDir != null){
            return runJacocoExecConversionMode();
        }
        if(discoverTestsOutput != null){ return runTestDiscoveryMode(); }
        if(coverageAllTests){ return runPerTestCoverageMode(); }
        if(shouldRunTcpPrioritization()){ return runTcpPrioritizationMode(); }

        DiffExtractor diffExtractor = new DiffExtractor();

        try {
            List<ChangedJavaFile> changedJavaFiles = diffExtractor.extractChangedJavaFiles(repoPath, baseRevision, headRevision);
            printChangedJavaFiles(changedJavaFiles);
            List<ChangedMethod> changedMethods = detectChangedMethods(changedJavaFiles);
            printChangedMethods(changedMethods);
            return 0;
        } catch (IOException e) {
            System.err.println("Error: Unable to extract Git diff.");
            System.err.println(e.getMessage());
            return 1;
        }
    }

    private void printChangedJavaFiles(List<ChangedJavaFile> changedJavaFiles) {
        System.out.println();
        if(changedJavaFiles.isEmpty()){
            System.out.println("No changed Java files found.");
            return;
        }
        System.out.println("Changed Java files:");
        for(ChangedJavaFile changedJavaFile : changedJavaFiles){
            String baseLines = formatLineRanges(changedJavaFile.changedLinesInBase());
            String headLines = formatLineRanges(changedJavaFile.changedLinesInHead());
            System.out.println("- " + formatChangedPath(changedJavaFile) + " base lines: " + baseLines + ", head lines: " + headLines);
        }
    }

    private String formatChangedPath(ChangedJavaFile changedJavaFile){
        if(changedJavaFile.oldPath() == null){
            return changedJavaFile.newPath() + " (added)";
        }
        if(changedJavaFile.newPath() == null){
            return changedJavaFile.oldPath() + " (deleted)";
        }
        if(!changedJavaFile.oldPath().equals(changedJavaFile.newPath())){
            return changedJavaFile.oldPath() + " -> " + changedJavaFile.newPath();
        }
        return changedJavaFile.newPath().toString();
    }

    private String formatLineRanges(List<Integer> lines){
        if(lines == null || lines.isEmpty()){
            return "[]";
        }
        List<Integer> sortedLines = lines.stream().distinct().sorted().toList();
        StringBuilder result = new StringBuilder();
        int rangeStart = sortedLines.getFirst();
        int previousLine = sortedLines.getFirst();
        for(int i = 1; i < sortedLines.size(); i++){
            int currentLine = sortedLines.get(i);
            if (currentLine != previousLine + 1) {
                appendRange(result, rangeStart, previousLine);
                rangeStart = currentLine;
            }
            previousLine = currentLine;
        }
        appendRange(result, rangeStart, previousLine);
        return result.toString();
    }

    private void appendRange(StringBuilder result, int rangeStart, int end) {
        if(!result.isEmpty()){ result.append(", "); }
        if(rangeStart == end) {
            result.append(rangeStart);
        } else{
            result.append(rangeStart).append("-").append(end);
        }
    }

    private List<ChangedMethod> detectChangedMethods(List<ChangedJavaFile> changedJavaFiles) throws Exception {
        RevisionMethodExtractor extractor = new RevisionMethodExtractor();
        ChangedMethodDetector changedMethodDetector = new ChangedMethodDetector();

        Map<MethodId, ChangedMethod> allChangedMethods = new LinkedHashMap<>();

        for(ChangedJavaFile changedJavaFile : changedJavaFiles){
            detectChangedMethods(
                    changedJavaFile.oldPath(),
                    baseRevision,
                    changedJavaFile.changedLinesInBase(),
                    extractor,
                    changedMethodDetector,
                    allChangedMethods
            );
            detectChangedMethods(
                    changedJavaFile.newPath(),
                    headRevision,
                    changedJavaFile.changedLinesInHead(),
                    extractor,
                    changedMethodDetector,
                    allChangedMethods
            );
        }
        return new ArrayList<>(allChangedMethods.values());
    }

    private void detectChangedMethods(
            Path filePath,
            String revision,
            List<Integer> changedLines,
            RevisionMethodExtractor extractor,
            ChangedMethodDetector changedMethodDetector,
            Map<MethodId, ChangedMethod> allChangedMethods
    ) throws IOException {
        if(filePath == null || changedLines.isEmpty()){
            return;
        }

        List<MethodRange> methodRanges = extractor.extractMethodsAtRevision(repoPath, revision, filePath);
        List<ChangedMethod> changedMethods = changedMethodDetector.detectChangedMethods(changedLines, methodRanges);
        for(ChangedMethod changedMethod : changedMethods){
            addChangedMethod(allChangedMethods, changedMethod);
        }
    }

    private void addChangedMethod(Map<MethodId, ChangedMethod> allChangedMethods, ChangedMethod changedMethod){
        MethodId methodId = changedMethod.methodRange().methodId();
        ChangedMethod existingMethod = allChangedMethods.get(methodId);
        if(existingMethod == null){
            allChangedMethods.put(methodId, changedMethod);
            return;
        }

        List<Integer> mergedLines = new ArrayList<>();
        mergedLines.addAll(existingMethod.changedLines());
        mergedLines.addAll(changedMethod.changedLines());
        allChangedMethods.put(methodId, new ChangedMethod(existingMethod.methodRange(), mergedLines.stream().distinct().sorted().toList()));
    }

    private void printChangedMethods(List<ChangedMethod> changedMethods){
        System.out.println();

        if(changedMethods.isEmpty()){
            System.out.println("No changed Java methods found.");
            return;
        }
        System.out.println("Changed Java methods:");
        for(ChangedMethod changedMethod : changedMethods){
            String changedLineRanges = formatLineRanges(changedMethod.changedLines());
            System.out.println("- " + changedMethod.methodRange().methodId() + " in " + changedMethod.methodRange().path() + " changed lines: " + changedLineRanges);
        }
    }

    private Integer runPerTestCoverageMode() {
        PerTestCoverageRunner runner = new PerTestCoverageRunner();
        try {
            Map<TestCaseId, Path> reports = runner.runCoverageForAllTests(repoPath, projectPath);
            System.out.println();
            System.out.println("JaCoCo per-test reports available:");
            for(Map.Entry<TestCaseId, Path> entry : reports.entrySet()){
                System.out.println("- " + entry.getKey() + " -> " + entry.getValue());
            }
            return 0;
        } catch (Exception e){
            System.err.println("Unable to run per-test coverage.");
            System.err.println(e.getMessage());
            return 1;
        }
    }

    private boolean shouldRunTcpPrioritization() {
        return output != null
                || graphOutput != null
                || impactGraphOutput != null
                || priorityRankingOutput != null;
    }

    private Integer runTestDiscoveryMode() {
        try {
            Path projectRoot = repoPath.resolve(projectPath).toAbsolutePath().normalize();
            Path testRoot = sourceTestsDir == null ? Path.of("src/test/java") : sourceTestsDir;
            List<TestCaseId> tests = new TestDiscoverer().discoverTests(projectRoot, testRoot);
            Path outputPath = resolveTcpImpactOutputPath(discoverTestsOutput, "discovered-tests.json", "--discover-tests-output");
            saveJson(Map.of(
                    "repo", repoPath.toAbsolutePath().normalize().toString(),
                    "projectPath", projectPath.toString(),
                    "sourceTestsDir", testRoot.toString(),
                    "testCount", tests.size(),
                    "tests", tests.stream().map(this::testDiscoveryPayload).toList()
            ), outputPath);
            System.out.println("Discovered test methods: " + tests.size());
            System.out.println("Output: " + outputPath);
            return 0;
        } catch (Exception e) {
            System.err.println("Unable to discover test methods.");
            System.err.println(e.getMessage());
            return 1;
        }
    }

    private Map<String, Object> testDiscoveryPayload(TestCaseId testCase) {
        return Map.of(
                "testId", testCase.className() + "::" + testCase.methodName(),
                "reportName", testCase.className() + "." + testCase.methodName()
        );
    }

    private Integer runTcpPrioritizationMode() {
        try {
            ParserFactory parserFactory = new ParserFactory();
            DiffExtractor diffExtractor = new DiffExtractor();
            List<ChangedJavaFile> changedJavaFiles = diffExtractor.extractChangedJavaFiles(repoPath, baseRevision, headRevision);
            List<ChangedMethod> changedMethods = detectChangedMethods(changedJavaFiles);
            List<Path> coverageReports = discoverCoverageReports();
            ImpactGraph impactGraph = new ImpactGraphBuilder(parserFactory).build(
                    repoPath,
                    projectPath,
                    changedMethods,
                    coverageReports,
                    sourceClassesDir,
                    sourceTestsDir
            );
            impactGraph = new RiskPropagator().propagate(impactGraph, maxDepth);
            Path graphPath = resolveGraphOutputPath();
            if(graphPath != null){
                saveJson(impactGraph, graphPath);
            }
            Path rankingPath = null;
            if(output != null){
                rankingPath = resolveProjectOutputPath(output);
                saveJson(priorityRankingPayload(impactGraph, changedMethods), rankingPath);
            } else if(priorityRankingOutput != null){
                rankingPath = resolveTcpImpactOutputPath(priorityRankingOutput, "priority-ranking.json", "--priority-ranking-output");
                saveJson(priorityRankingPayload(impactGraph, changedMethods), rankingPath);
            }

            System.out.println();
            System.out.println("TCP Impact prioritization completed:");
            System.out.println("- Method nodes: " + impactGraph.nodes().size());
            System.out.println("- Impact edges: " + impactGraph.edges().size());
            System.out.println("- Changed methods mapped: " + changedMethods.size());
            System.out.println("- Coverage reports parsed: " + coverageReports.size());
            System.out.println("- Max depth: " + maxDepth);
            if(graphPath != null){
                System.out.println("- Graph: " + graphPath);
            }
            if(rankingPath != null){
                System.out.println("- Ranking: " + rankingPath);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Unable to run TCP Impact prioritization.");
            System.err.println(e.getMessage());
            return 1;
        }
    }

    private Path resolveGraphOutputPath() {
        if(graphOutput != null){
            return resolveProjectOutputPath(graphOutput);
        }
        if(impactGraphOutput != null){
            return resolveTcpImpactOutputPath(impactGraphOutput, "impact-graph.json", "--impact-graph-output");
        }
        if(priorityRankingOutput != null){
            return resolveTcpImpactOutputPath(Path.of("impact-graph.json"), "impact-graph.json", "--impact-graph-output");
        }
        return null;
    }

    private Map<String, Object> priorityRankingPayload(ImpactGraph impactGraph, List<ChangedMethod> changedMethods) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repo", repoPath.toAbsolutePath().normalize().toString());
        payload.put("projectPath", projectPath.toString());
        payload.put("base", baseRevision);
        payload.put("head", headRevision);
        payload.put("maxDepth", maxDepth);
        payload.put("sourceClassesDir", sourceClassesDir == null ? null : sourceClassesDir.toString());
        payload.put("sourceTestsDir", sourceTestsDir == null ? null : sourceTestsDir.toString());
        payload.put("changedMethods", changedMethods.stream().map(this::changedMethodPayload).toList());
        payload.put("rankedTests", new TestPriorityRanker().rank(impactGraph));
        return payload;
    }

    private Map<String, Object> changedMethodPayload(ChangedMethod changedMethod) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("methodId", changedMethod.methodRange().methodId().toString());
        payload.put("path", changedMethod.methodRange().path().toString());
        payload.put("startLine", changedMethod.methodRange().start());
        payload.put("endLine", changedMethod.methodRange().end());
        payload.put("changedLines", changedMethod.changedLines());
        return payload;
    }

    private Path resolveTcpImpactOutputPath(Path requestedOutput, String defaultFileName, String optionName) {
        Path tcpImpactDir = repoPath
                .resolve(projectPath)
                .toAbsolutePath()
                .normalize()
                .resolve(".tcpimpact")
                .normalize();
        Path relativeOutput = normalizeTcpImpactOutput(requestedOutput, defaultFileName, optionName);
        Path outputPath = tcpImpactDir.resolve(relativeOutput).normalize();
        if(!outputPath.startsWith(tcpImpactDir)){
            throw new IllegalArgumentException(optionName + " must stay inside the analyzed project's .tcpimpact directory.");
        }
        return outputPath;
    }

    private Path normalizeTcpImpactOutput(Path requestedOutput, String defaultFileName, String optionName) {
        Path normalizedOutput = requestedOutput.normalize();
        if(normalizedOutput.isAbsolute()){
            throw new IllegalArgumentException(optionName + " must be a file name or relative path inside .tcpimpact.");
        }
        Path defaultPath = Path.of(defaultFileName);

        if(normalizedOutput.getNameCount() == 0 || normalizedOutput.toString().isBlank() || normalizedOutput.toString().equals(".")){
            return defaultPath;
        }

        if(normalizedOutput.getName(0).toString().equals(".tcpimpact")){
            if(normalizedOutput.getNameCount() == 1){
                return defaultPath;
            }
            return normalizedOutput.subpath(1, normalizedOutput.getNameCount());
        }
        return normalizedOutput;
    }

    private Path resolveProjectOutputPath(Path requestedOutput) {
        if(requestedOutput.isAbsolute()){
            return requestedOutput.toAbsolutePath().normalize();
        }
        return repoPath
                .resolve(projectPath)
                .resolve(requestedOutput)
                .toAbsolutePath()
                .normalize();
    }

    private void saveJson(Object payload, Path outputPath) throws IOException {
        Path parent = outputPath.getParent();
        if(parent != null){
            Files.createDirectories(parent);
        }
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), payload);
    }

    private List<Path> discoverCoverageReports() throws IOException {
        Path tcpImpactDir = repoPath
                .resolve(projectPath)
                .toAbsolutePath()
                .normalize()
                .resolve(".tcpimpact")
                .normalize();
        Path optimizedXmlDir = tcpImpactDir.resolve("per-test-coverage").resolve("xml");
        Path legacyCoverageDir = tcpImpactDir.resolve("per-test-coverage");
        List<Path> coverageRoots = coverageDir == null
                ? List.of(optimizedXmlDir, legacyCoverageDir)
                : List.of(resolveProjectRelativePath(coverageDir), optimizedXmlDir, legacyCoverageDir);
        List<Path> reports = new ArrayList<>();
        for(Path coverageRoot : coverageRoots){
            if(!Files.exists(coverageRoot)){
                continue;
            }
            try(var files = Files.walk(coverageRoot)){
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".xml"))
                        .forEach(reports::add);
            }
        }
        return reports.stream().distinct().toList();
    }

    private Integer runJacocoExecConversionMode() {
        try {
            if(jacocoExecDir == null || jacocoReportMap == null || jacocoClassesDir == null || jacocoXmlOutputDir == null){
                throw new IllegalArgumentException("--jacoco-exec-dir, --jacoco-report-map, --jacoco-classes-dir, and --jacoco-xml-output-dir are required together.");
            }
            Path execDir = resolveProjectRelativePath(jacocoExecDir);
            Path reportMapPath = resolveProjectRelativePath(jacocoReportMap);
            Path classesDir = resolveProjectRelativePath(jacocoClassesDir);
            Path xmlOutputDir = resolveProjectRelativePath(jacocoXmlOutputDir);
            Map<String, String> reportNames = OBJECT_MAPPER.readValue(reportMapPath.toFile(), new TypeReference<>() {});
            JacocoReportGenerator reportGenerator = new JacocoReportGenerator();

            int converted = 0;
            for(Map.Entry<String, String> entry : reportNames.entrySet()){
                Path execFile = execDir.resolve(entry.getKey()).normalize();
                if(!Files.exists(execFile)){
                    System.err.println("Warning: missing JaCoCo exec file: " + execFile);
                    continue;
                }
                String reportName = entry.getValue();
                Path xmlReport = xmlOutputDir.resolve(safeFileName(reportName) + ".xml").normalize();
                reportGenerator.generateXmlReport(execFile, classesDir, xmlReport, reportName);
                converted++;
            }

            System.out.println("Converted JaCoCo exec files: " + converted);
            System.out.println("XML output: " + xmlOutputDir);
            return 0;
        } catch (Exception e) {
            System.err.println("Unable to convert JaCoCo exec files.");
            System.err.println(e.getMessage());
            return 1;
        }
    }

    private Path resolveProjectRelativePath(Path path) {
        if(path.isAbsolute()){
            return path.toAbsolutePath().normalize();
        }
        return repoPath
                .resolve(projectPath)
                .resolve(path)
                .toAbsolutePath()
                .normalize();
    }

    private String safeFileName(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

}
