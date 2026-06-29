package it.unicam.tcpimpact.git;
import java.nio.file.Path;
import java.util.List;

/**
 * Represents a Java source file affected by a Git change.
 *
 * @param path path of the changed Java file, relatively to the root of the analyzed repo
 * @param changedLines line numbers changed in the head revision
 */
public record ChangedJavaFile(Path path, List<Integer> changedLines) {}