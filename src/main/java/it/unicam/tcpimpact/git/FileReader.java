package it.unicam.tcpimpact.git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class FileReader {

    /**
     * Reads file contents from a specific Git revision.
     * Note: for now it duplicates some code from DiffExtractor, in the future we might refactor it.
     *
     * @param path path to the Git repository
     * @param revision Git revision
     * @param filePath path of the file relative to the repository root
     * @return file content at the given revision
     * @throws IOException if the repository or file cannot be read
     */
    public String readFileAtRevision(Path path, String revision, Path filePath) throws IOException {
        Repository repository = openRepository(path);

        try (repository; RevWalk revWalk = new RevWalk(repository)) {

            ObjectId commitId = resolveCommit(repository, revision);
            RevCommit commit = revWalk.parseCommit(commitId);
            String gitPath = filePath.toString().replace("\\", "/");

            try (TreeWalk treewalk = TreeWalk.forPath(repository, gitPath, commit.getTree())) {
                if (treewalk == null)
                    throw new IllegalArgumentException("File not found at revision " + revision + ": " + filePath);

                ObjectId objectId = treewalk.getObjectId(0);
                ObjectLoader loader = repository.open(objectId);

                return new String(loader.getBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private Repository openRepository(Path path) throws IOException {
        return new FileRepositoryBuilder().setWorkTree(path.toFile()).findGitDir(path.toFile()).build();
    }

    private ObjectId resolveCommit(Repository repository, String revision) throws IOException {
        ObjectId commitId = repository.resolve(revision + "^{commit}");
        if(commitId == null)
            throw new IllegalArgumentException("Cannot resolve Git revision: " + revision);
        return commitId;
    }
}
