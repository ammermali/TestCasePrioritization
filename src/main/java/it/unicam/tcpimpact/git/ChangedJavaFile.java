package it.unicam.tcpimpact.git;
import java.nio.file.Path;
import java.util.List;

/**
 * Represents a Java source file affected by a Git change.
 *
 * @param oldPath path of the original Java file, relative to the root of the analyzed repo
 * @param newPath path of the changed Java file, relative to the root of the analyzed repo
 * @param changedLinesInBase changed line numbers in the base revision
 * @param changedLinesInHead changed line numbers in the head revision
 */
public record ChangedJavaFile(Path oldPath, Path newPath, List<Integer> changedLinesInBase, List<Integer> changedLinesInHead) {}
