package it.unicam.tcpimpact.cli;
import it.unicam.tcpimpact.git.GitRepositoryValidator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.nio.file.Path;
import java.util.concurrent.Callable;


/**
 * Main command of the TCP Impact prototype.
 *
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
            names = "--repo",
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
     *
     * The method prints the received configuration, validates the repository
     * path, and returns an exit code compatible with command-line execution.
     *
     * Note: at the moment, this method may raise an error in the terminal.
     * Ignore it, as it is related to the JGit logger and isn't influential
     * in any way for the prototype.
     *
     * @return 0 if the repository is valid, 1 otherwise
     */
    @Override
    public Integer call(){
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

        return 0;
    }
}
