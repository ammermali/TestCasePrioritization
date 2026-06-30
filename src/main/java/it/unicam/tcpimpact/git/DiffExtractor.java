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
                ChangedJavaFile changedJavaFile = createChangedJavaFile(entry, fileHeader);
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
     * @return opened JGit repository
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
        ObjectId commitId = repository.resolve(revision + "^{commit}");
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
        return isJavaPath(entry.getOldPath()) || isJavaPath(entry.getNewPath());
    }

    private boolean isJavaPath(String path){
        return path != null
                && !DiffEntry.DEV_NULL.equals(path)
                && path.endsWith(".java");
    }

    private ChangedJavaFile createChangedJavaFile(DiffEntry entry, FileHeader fileHeader){
        Path oldPath = toPathOrNull(entry.getOldPath());
        Path newPath = toPathOrNull(entry.getNewPath());
        Set<Integer> changedLinesInBase = new TreeSet<>();
        Set<Integer> changedLinesInHead = new TreeSet<>();

        for(Edit edit : fileHeader.toEditList()){
            addLineNumbers(changedLinesInBase, edit.getBeginA(), edit.getEndA());
            addLineNumbers(changedLinesInHead, edit.getBeginB(), edit.getEndB());
        }

        return new ChangedJavaFile(
                oldPath,
                newPath,
                new ArrayList<>(changedLinesInBase),
                new ArrayList<>(changedLinesInHead)
        );
    }

    private Path toPathOrNull(String gitPath){
        if(gitPath == null || DiffEntry.DEV_NULL.equals(gitPath)){
            return null;
        }
        return Path.of(gitPath);
    }

    private void addLineNumbers(Set<Integer> changedLines, int begin, int end){
        for(int line = begin + 1; line <= end; line++){
            changedLines.add(line);
        }
    }
}
