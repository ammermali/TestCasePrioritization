package it.unicam.tcpimpact.git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class DiffExtractor {

    /** Extracts changed Java files between two Git revisions.
     *
     * @param path path to the Git repository to analyze
     * @param baseRevision starting point
     * @param headRevision ending point
     * @return List of changed Java files with changed Line numbers
     * @throws IOException if the repository or diff cannot be read
     */

    public List<ChangedJavaFile> extractChangedJavaFiles(Path path, String baseRevision, String headRevision) throws IOException {
        Repository repository = openRepository(path);
        RevWalk revWalk = new RevWalk(repository);
        DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);

        try {
            ObjectId baseCommitId = resolveCommit(repository, baseRevision);
            ObjectId headCommitId = resolveCommit(repository, headRevision);

            RevCommit baseCommit = revWalk.parseCommit(baseCommitId);
            RevCommit headCommit = revWalk.parseCommit(headCommitId);

            diffFormatter.setRepository(repository);
            diffFormatter.setDiffComparator(RawTextComparator.DEFAULT);
            diffFormatter.setDetectRenames(true);

            List<DiffEntry> diffEntries = diffFormatter.scan(baseCommit.getTree(), headCommit.getTree());
            List<ChangedJavaFile> changedJavaFiles = new ArrayList<>();

            for(DiffEntry entry : diffEntries){
                if(!isSupportedJavaChange(entry)) { continue; }

                FileHeader fileHeader = diffFormatter.toFileHeader(entry);
                Path filePath = Path.of(entry.getNewPath());
                List<Integer> changedLines = extractChangedLines(fileHeader);

                ChangedJavaFile changedJavaFile = new ChangedJavaFile(filePath, changedLines);
                changedJavaFiles.add(changedJavaFile);
            }
            return changedJavaFiles;
        } finally {
            diffFormatter.close();
            revWalk.close();
            repository.close();
        }
    }

    /**
     * Opens the Git repository located at the given path.
     *
     * @param path path to the repository in the work tree
     * @return openeed JGit repository
     * @throws IOException if the repository cannot be opened
     */

    private Repository openRepository(Path path) throws IOException {
        return new FileRepositoryBuilder().setWorkTree(path.toFile()).findGitDir(path.toFile()).build();
    }


    /**
     * Resolves a Git revision string to a commit object id.
     *
     * @param repository Git repository
     * @param revision revision string
     * @return resolved commit id
     * @throws IOException if the revision cannot be resolved
     */

    private ObjectId resolveCommit(Repository repository, String revision) throws IOException {
        ObjectId commitId = repository.resolve(revision);
        if(commitId == null){ throw new IllegalArgumentException("Cannot resolve Git revision: " + revision); }
        return commitId;
    }

    /**
     * Checks whether a diff entry is a Java file change supported by the prototype.
     *
     * @param entry Git diff entry
     * @return {@code true} if the entry represents a supported Java file change
     */

    private boolean isSupportedJavaChange(DiffEntry entry){
        return entry.getChangeType() != DiffEntry.ChangeType.DELETE
                && entry.getNewPath() != null
                && entry.getNewPath().endsWith(".java");
    }

    /**
     * Extracts changed line numbers from a file diff.
     *
     * @param fileHeader diff header of a changed file
     * @return sorted list of changed line numbers in the head revision
     */

    private List<Integer> extractChangedLines(FileHeader fileHeader){
        Set<Integer> changedLines = new TreeSet<>();
        for(Edit edit: fileHeader.toEditList()){
            for(int line = edit.getBeginB(); line < edit.getEndB(); line++){
                changedLines.add(line+1); // JGit uses zero-indexed lines internally.
            }
        }
        return new ArrayList<>(changedLines);
    }
}
