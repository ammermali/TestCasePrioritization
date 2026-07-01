package it.unicam.tcpimpact.coverage;

import com.github.javaparser.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TestDiscoverer {

    private final JavaParser javaParser;

    public TestDiscoverer() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.javaParser = new JavaParser(config);
    }

    /**
     * Discovers JUnit test methods under src/test/java.
     * TODO: add for other annotations like @RepeatedTest, @TestFactory, etc.
     *
     * @param gradleProjectPath path to the Gradle project root
     * @return List of discovered test methods
     * @throws IOException if test files cannot be read
     */

    public List<TestCaseId> discoverTests(Path gradleProjectPath) throws IOException {
        Path testSourceRoot = gradleProjectPath.resolve("src/test/java");

        if(!Files.exists(testSourceRoot))
            return List.of();

        List<TestCaseId> testCases = new ArrayList<>();

        try (var files = Files.walk(testSourceRoot)){
            List<Path> testFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();

            for (Path path : testFiles)
                testCases.addAll(discoverTestsInFile(path));
        }

        return testCases;
    }

    private List<TestCaseId> discoverTestsInFile(Path path) throws IOException {
        ParseResult<CompilationUnit> parseResult = javaParser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(path));

        if(!parseResult.isSuccessful() || parseResult.getResult().isEmpty())
            throw new IllegalArgumentException("Failed to parse file: " + path + "\n" + parseResult.getProblems());

        CompilationUnit compilationUnit = parseResult.getResult().get();
        String packageName = compilationUnit.getPackageDeclaration().map(NodeWithName::getNameAsString).orElse("");

        List<TestCaseId> testCases = new ArrayList<>();

        for(MethodDeclaration method : compilationUnit.findAll(MethodDeclaration.class)) {
            if(!isTestMethod(method))
                continue;

            String className = resolveClassName(method);

            if(className.isBlank())
                continue;

            String qualifiedClassName = packageName.isBlank() ? className : packageName + "." + className;
            testCases.add(new TestCaseId(qualifiedClassName, method.getNameAsString()));
        }
        
        return testCases;
    }

    private boolean isTestMethod(MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(annotation -> annotation.getNameAsString().equals("Test"));
    }

    private String resolveClassName(Node node) {
        Node currentNode = node;

        while(currentNode.getParentNode().isPresent()){

            currentNode = currentNode.getParentNode().get();

            if(currentNode instanceof ClassOrInterfaceDeclaration classDeclaration)
                return classDeclaration.getNameAsString();
        }

        return "";
    }
}
