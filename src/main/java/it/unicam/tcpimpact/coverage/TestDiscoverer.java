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
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class TestDiscoverer {
    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Test",
            "org.junit.Test",
            "org.junit.jupiter.api.Test",
            "ParameterizedTest",
            "org.junit.jupiter.params.ParameterizedTest",
            "RepeatedTest",
            "org.junit.jupiter.api.RepeatedTest"
    );

    private final JavaParser javaParser;

    public TestDiscoverer() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.javaParser = new JavaParser(config);
    }

    /**
     * Discovers JUnit test methods under src/test/java.
     * @param gradleProjectPath path to the Gradle project root
     * @return List of discovered test methods
     * @throws IOException if test files cannot be read
     */

    public List<TestCaseId> discoverTests(Path gradleProjectPath) throws IOException {
        return discoverTests(gradleProjectPath, Path.of("src/test/java"));
    }

    public List<TestCaseId> discoverTests(Path projectPath, Path testSourceRootPath) throws IOException {
        Path testSourceRoot = testSourceRootPath.isAbsolute()
                ? testSourceRootPath.toAbsolutePath().normalize()
                : projectPath.resolve(testSourceRootPath).toAbsolutePath().normalize();

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
        return method.getAnnotations()
                .stream()
                .map(annotation -> annotation.getNameAsString())
                .anyMatch(annotationName -> TEST_ANNOTATIONS.contains(annotationName) || TEST_ANNOTATIONS.contains(simpleName(annotationName)));
    }

    private String resolveClassName(Node node) {
        List<String> classNames = new ArrayList<>();
        Node currentNode = node;

        while(currentNode.getParentNode().isPresent()){

            currentNode = currentNode.getParentNode().get();

            if(currentNode instanceof ClassOrInterfaceDeclaration classDeclaration)
                classNames.add(classDeclaration.getNameAsString());
        }

        Collections.reverse(classNames);
        return String.join(".", classNames);
    }

    private String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
