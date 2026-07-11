package it.unicam.tcpimpact.graph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    static Path testJavaRoot(Path repositoryPath, Path projectPath) {
        return repositoryPath
                .resolve(projectPath)
                .resolve("src")
                .resolve("test")
                .resolve("java")
                .toAbsolutePath()
                .normalize();
    }

    static List<Path> existingJavaRoots(Path repositoryPath, Path projectPath) {
        return List.of(mainJavaRoot(repositoryPath, projectPath), testJavaRoot(repositoryPath, projectPath))
                .stream()
                .filter(Files::exists)
                .toList();
    }

    static List<Path> existingJavaRoots(
            Path repositoryPath,
            Path projectPath,
            Path sourceClassesDir,
            Path sourceTestsDir
    ) {
        List<Path> roots = new ArrayList<>();
        roots.add(sourceRootOrDefault(repositoryPath, projectPath, sourceClassesDir, mainJavaRoot(repositoryPath, projectPath)));
        roots.add(sourceRootOrDefault(repositoryPath, projectPath, sourceTestsDir, testJavaRoot(repositoryPath, projectPath)));
        return roots.stream()
                .filter(Files::exists)
                .distinct()
                .toList();
    }

    static Path sourceRootOrDefault(Path repositoryPath, Path projectPath, Path configuredRoot, Path defaultRoot) {
        if(configuredRoot == null){
            return defaultRoot;
        }
        if(configuredRoot.isAbsolute()){
            return configuredRoot.toAbsolutePath().normalize();
        }
        return repositoryPath
                .resolve(projectPath)
                .resolve(configuredRoot)
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
        if(!Files.exists(sourceRoot)){
            return List.of();
        }
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
