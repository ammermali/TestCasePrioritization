package it.unicam.tcpimpact.graph;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import it.unicam.tcpimpact.model.MethodRange;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Scans the production source tree of a Java project and returns the methods declared in it with their source ranges.
 */
public class ProjectMethodScanner {
    private final ParserFactory factory;

    public ProjectMethodScanner(ParserFactory factory) {
        this.factory = factory;
    }

    /**
     * Finds all resolvable method declarations under {@code src/main/java}.
     *
     * @param path path to the Git repository
     * @param projectPath path of the Java project inside the repository
     * @return source ranges of the methods declared in the project
     * @throws IOException if the source tree cannot be read
     */
    public List<MethodRange> scan(Path path, Path projectPath) throws IOException {
        Path root = ProjectJavaSources.mainJavaRoot(path, projectPath);
        JavaParser parser = factory.create(root);
        List<MethodRange> methods = new ArrayList<>();
        for (Path javaFile : ProjectJavaSources.listJavaFiles(root)) {
            ParseResult<CompilationUnit> parseResult = parser.parse(javaFile);
            if (parseResult.getResult().isEmpty()) {
                System.out.println("Unable to parse " + javaFile);
                continue;
            }
            Path relativePath = ProjectJavaSources.repositoryRelativePath(path, javaFile);
            CompilationUnit compilationUnit = parseResult.getResult().get();
            for (MethodDeclaration methodDeclaration : compilationUnit.findAll(MethodDeclaration.class)) {
                toMethodRange(methodDeclaration, relativePath).ifPresent(methods::add);
            }
        }
        return methods;
    }

    private Optional<MethodRange> toMethodRange(MethodDeclaration methodDeclaration, Path relativePath) {
        try {
            var begin = methodDeclaration.getBegin();
            var end = methodDeclaration.getEnd();
            if (begin.isEmpty() || end.isEmpty()) {
                return Optional.empty();
            }
            MethodRange range = new MethodRange(
                    ResolvedMethodIds.from(methodDeclaration.resolve()),
                    relativePath,
                    begin.get().line,
                    end.get().line
            );
            return Optional.of(range);
        } catch (RuntimeException exception) {
            System.out.println("Unable to resolve method: " + methodDeclaration.getNameAsString());
            return Optional.empty();
        }
    }
}
