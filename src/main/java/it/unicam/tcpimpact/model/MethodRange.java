package it.unicam.tcpimpact.model;
import java.nio.file.Path;

/**
 * Represents the source-code range occupied by a Java method.
 *
 * @param methodId identifier of the method
 * @param path relative path of the Java source file
 * @param start first line of the method
 * @param end last line of the method
 */

public record MethodRange(MethodId methodId, Path path, int start, int end) { }
