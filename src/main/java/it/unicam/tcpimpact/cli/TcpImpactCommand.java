package it.unicam.tcpimpact.cli;
import it.unicam.tcpimpact.git.GitRepositoryValidator;
import it.unicam.tcpimpact.model.ChangedMethod;
import it.unicam.tcpimpact.model.MethodId;
import it.unicam.tcpimpact.model.MethodRange;
import it.unicam.tcpimpact.parser.ChangedMethodDetector;
import it.unicam.tcpimpact.parser.RevisionMethodExtractor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.io.IOException;
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
        description = "Prototype tool for Git-aware test case prioritization"
)



public class TcpImpactCommand implements Callable<Integer> {

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
        int rangeStart = sortedLines.get(0);
        int previousLine = sortedLines.get(0);
        for(int i = 1; i < sortedLines.size(); i++){
            int currentLine = sortedLines.get(i);
            if(currentLine == previousLine + 1){
                previousLine = currentLine;
            } else {
                appendRange(result,rangeStart, previousLine);
                rangeStart = currentLine;
                previousLine = currentLine;
            }
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

}
