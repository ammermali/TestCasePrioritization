package it.unicam.tcpimpact.git;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class GitRepositoryValidator {

    /**
     * Checks whether the given path points to a valid Git repository.
     *
     * The method accepts both repository root directories and directories
     * inside a Git work tree, because JGit is able to search for the associated
     * .git directory.
     *
     * @param path path to the directory that should be validated
     * @return {@code true}true if the path belongs to a valid Git repository,
     *         {@code false} otherwise
     */
    public boolean isGitRepository(Path path) {
        File directory = path.toFile();
        try{
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            builder.setWorkTree(directory).findGitDir(directory).build().close();
            return true;
        } catch (IOException | IllegalArgumentException exception){
            return false;
        }
    }
}
