package it.unicam.tcpimpact.coverage.optimized;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


public class GradleCoverageRunner {

    /**
     * Runs the full test suite once, producing one .exec file per test
     * method under execOutputDir.
     *
     * @param repoPath root path of the Git repository
     * @param projectPath path of the Gradle project inside the repository
     * @param initScript generated Gradle init script wiring in the agent + extension
     * @param execOutputDir directory where per-test .exec files should be written
     * @throws IOException if the process cannot be started or fails
     * @throws InterruptedException if the Gradle process is interrupted
     */
    public void runSuiteWithPerTestCoverage(Path repoPath, Path projectPath, Path initScript, Path execOutputDir)
            throws IOException, InterruptedException {

        if (repoPath == null || projectPath == null)
            throw new IllegalArgumentException("Paths must not be null");

        Path gradleProjectPath = repoPath.resolve(projectPath).toAbsolutePath().normalize();
        Path gradleWrapper = gradleProjectPath.resolve(resolveGradleWrapperName());

        if (!Files.exists(gradleWrapper))
            throw new IllegalArgumentException("Gradle wrapper not found: " + gradleWrapper);

        List<String> command = buildCommand(gradleWrapper, initScript, execOutputDir);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gradleProjectPath.toFile());
        pb.inheritIO();

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0)
            throw new IOException("Gradle test execution failed with exit code: " + exitCode);
    }

    private List<String> buildCommand(Path gradleWrapper, Path initScript, Path execOutputDir) {
        List<String> command = new ArrayList<>();
        command.add(gradleWrapper.toString());
        command.add("--init-script");
        command.add(initScript.toAbsolutePath().toString());
        command.add("-Dtcpimpact.execOutputDir=" + execOutputDir.toAbsolutePath());
        command.add("test");
        return command;
    }

    private String resolveGradleWrapperName() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        return isWindows ? "gradlew.bat" : "gradlew";
    }
}