package it.unicam.tcpimpact.graph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared helpers for locating Java production sources inside the analyzed repository.
 */
final class ProjectJavaSources {
    private static final String JAVA_EXTENSION = ".java";

    private ProjectJavaSources() {
    }

    /**
     * Resolves the conventional Maven/Gradle production source root.
     *
     * @param repositoryPath path to the Git repository
     * @param projectPath path of the Java project inside the repository
     * @return normalized absolute path to {@code src/main/java}
     */
    static Path mainJavaRoot(Path repositoryPath, Path projectPath) {
        return repositoryPath
                .resolve(projectPath)
                .resolve("src")
                .resolve("main")
                .resolve("java")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Lists Java source files below the given root.
     *
     * @param sourceRoot source root to scan
     * @return Java files contained in the source tree
     * @throws IOException if the source tree cannot be walked
     */
    static List<Path> listJavaFiles(Path sourceRoot) throws IOException {
        try (var files = Files.walk(sourceRoot)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(ProjectJavaSources::isJavaSource)
                    .toList();
        }
    }

    /**
     * Converts an absolute source file path to a repository-relative path.
     *
     * @param repositoryPath path to the Git repository
     * @param sourceFile Java source file
     * @return path relative to the repository root
     */
    static Path repositoryRelativePath(Path repositoryPath, Path sourceFile) {
        Path repositoryRoot = repositoryPath.toAbsolutePath().normalize();
        return repositoryRoot.relativize(sourceFile.toAbsolutePath().normalize());
    }

    private static boolean isJavaSource(Path path) {
        return path.toString().endsWith(JAVA_EXTENSION);
    }
}
