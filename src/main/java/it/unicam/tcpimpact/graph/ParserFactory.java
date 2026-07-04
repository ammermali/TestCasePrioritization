package it.unicam.tcpimpact.graph;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.nio.file.Path;

/**
 * Factory for JavaParser instances configured with symbol resolution.
 */
public class ParserFactory {

    /**
     * Creates a parser able to resolve JDK classes and project classes under the provided source root.
     *
     * @param sourceRoot root directory containing the project's Java sources
     * @return configured parser
     */
    public JavaParser create(Path sourceRoot) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());
        solver.add(new JavaParserTypeSolver(sourceRoot));
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(solver);
        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        return new JavaParser(config);
    }
}
