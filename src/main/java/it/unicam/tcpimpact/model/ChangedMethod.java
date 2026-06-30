package it.unicam.tcpimpact.model;
import java.nio.file.Path;
import java.util.List;

/**
 * Represents a Java method affected by a Git change.
 *
 * @param methodRange method changed (identified by its source code position)
 * @param changedLines changed lines that fall inside the method body
 */

public record ChangedMethod(MethodRange methodRange, List<Integer> changedLines) { }
