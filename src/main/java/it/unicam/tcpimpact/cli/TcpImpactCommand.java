package it.unicam.tcpimpact.cli;
import it.unicam.tcpimpact.git.GitRepositoryValidator;
import it.unicam.tcpimpact.model.ChangedMethod;
import it.unicam.tcpimpact.model.MethodRange;
import it.unicam.tcpimpact.parser.ChangedMethodDetector;
import it.unicam.tcpimpact.parser.MethodExtractor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import it.unicam.tcpimpact.git.ChangedJavaFile;
import it.unicam.tcpimpact.git.DiffExtractor;


/**
 * Main command of the TCP Impact prototype.
 * This command receives the path of a Git repository and two Git revisions
 * representing the range of changes to analyze. At this stage of the prototype,
 * the command only validates that the provided path points to a valid Git repository.
 */

@Command(
        name = "tcp-impact",
        mixinStandardHelpOptions = true,
        version = "tcp-impact 0.1.0",
        description = "Prototype tool for Git-aware test case prioritizatin"
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
     * path, and returns an exit code compatible with command-line execution.
     * Note: at the moment, this method may raise an error in the terminal.
     * Ignore it, as it is related to the JGit logger and isn't influential
     * in any way for the prototype.
     *
     * @return 0 if the repository is valid, 1 otherwise (CLI exit code)
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
            String ranges = formatLineRanges(changedJavaFile.changedLines());
            System.out.println("- " + changedJavaFile.path() + " changed lines: " + ranges);
        }
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
        MethodExtractor extractor = new MethodExtractor();
        ChangedMethodDetector changedMethodDetector = new ChangedMethodDetector();

        List<ChangedMethod> allChangedMethods = new ArrayList<>();

        for(ChangedJavaFile changedJavaFile : changedJavaFiles){
            Path absolutePath = repoPath.toAbsolutePath().normalize().resolve(changedJavaFile.path()).normalize();
            List<MethodRange> methodRanges = extractor.extractMethods(absolutePath, changedJavaFile.path());
            List<ChangedMethod> changedMethods = changedMethodDetector.detectChangedMethods(changedJavaFile, methodRanges);
            allChangedMethods.addAll(changedMethods);
        }
        return allChangedMethods;
    }

    private void printChangedMethods(List<ChangedMethod> changedMethods){
        System.out.println();

        if(changedMethods.isEmpty()){
            System.out.println("No changed Java files found.");
            return;
        }
        System.out.println("Changed Java methods:");
        for(ChangedMethod changedMethod : changedMethods){
            String changedLineRanges = formatLineRanges(changedMethod.changedLines());
            System.out.println("- " + changedMethod.methodRange().methodId() + " in " + changedMethod.methodRange().path() + " changed lines: " + changedLineRanges);
        }
    }

}
