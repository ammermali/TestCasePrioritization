package it.unicam.tcpimpact.graph;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import it.unicam.tcpimpact.graph.model.MethodCallEdge;
import it.unicam.tcpimpact.graph.model.MethodCallGraph;
import it.unicam.tcpimpact.model.MethodId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a method-level call graph for the production source tree of a project.
 */
public class CallGraphBuilder {
    private final ParserFactory factory;

    public CallGraphBuilder(ParserFactory factory) {
        this.factory = factory;
    }

    /**
     * Builds a graph where each edge points from the caller method to the project method it invokes.
     *
     * @param path path to the Git repository
     * @param projectPath path of the Java project inside the repository
     * @return method call graph for project methods under {@code src/main/java}
     * @throws IOException if the source tree cannot be read
     */
    public MethodCallGraph build(Path path, Path projectPath) throws IOException {
        Path root = ProjectJavaSources.mainJavaRoot(path, projectPath);
        JavaParser parser = factory.create(root);
        List<ParsedCompilationUnit> compilationUnits = parseJavaFiles(parser, ProjectJavaSources.listJavaFiles(root));
        Set<MethodId> projectMethods = collectProjectMethods(compilationUnits);
        Set<MethodCallEdge> edges = collectCallEdges(compilationUnits, projectMethods);
        return new MethodCallGraph(projectMethods, edges);
    }

    private List<ParsedCompilationUnit> parseJavaFiles(JavaParser parser, List<Path> javaFiles) throws IOException {
        List<ParsedCompilationUnit> compilationUnits = new ArrayList<>();
        for (Path javaFile : javaFiles) {
            ParseResult<CompilationUnit> parseResult = parser.parse(javaFile);
            parseResult.getResult()
                    .map(ParsedCompilationUnit::new)
                    .ifPresent(compilationUnits::add);
        }
        return compilationUnits;
    }

    private Set<MethodId> collectProjectMethods(List<ParsedCompilationUnit> compilationUnits) {
        Set<MethodId> projectMethods = new HashSet<>();
        for (ParsedCompilationUnit parsed : compilationUnits) {
            for (MethodDeclaration methodDeclaration : parsed.compilationUnit().findAll(MethodDeclaration.class)) {
                resolveMethod(methodDeclaration).ifPresent(projectMethods::add);
            }
        }
        return projectMethods;
    }

    private Set<MethodCallEdge> collectCallEdges(List<ParsedCompilationUnit> compilationUnits, Set<MethodId> projectMethods) {
        Set<MethodCallEdge> edges = new HashSet<>();
        for (ParsedCompilationUnit parsed : compilationUnits) {
            for (MethodDeclaration methodDeclaration : parsed.compilationUnit().findAll(MethodDeclaration.class)) {
                Optional<MethodId> caller = resolveMethod(methodDeclaration);
                if (caller.isEmpty()) {
                    continue;
                }
                for (MethodCallExpr expression : methodDeclaration.findAll(MethodCallExpr.class)) {
                    Optional<MethodId> callee = resolveMethodCall(expression);
                    if (callee.isPresent() && projectMethods.contains(callee.get())) {
                        edges.add(new MethodCallEdge(caller.get(), callee.get()));
                    }
                }
            }
        }
        return edges;
    }

    private Optional<MethodId> resolveMethod(MethodDeclaration methodDeclaration) {
        try {
            return Optional.of(ResolvedMethodIds.from(methodDeclaration.resolve()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<MethodId> resolveMethodCall(MethodCallExpr expression) {
        try {
            return Optional.of(ResolvedMethodIds.from(expression.resolve()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private record ParsedCompilationUnit(CompilationUnit compilationUnit) { }
}
