package it.unicam.tcpimpact.parser;


import com.github.javaparser.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import it.unicam.tcpimpact.model.MethodId;
import it.unicam.tcpimpact.model.MethodRange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MethodExtractor {

    private final JavaParser javaParser;

    public MethodExtractor(){
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.javaParser = new JavaParser(parserConfiguration);
    }

    /**
     * Extracts all methods and constructors declared in the given Java file.
     *
     * @param sourceCode Java source code
     * @param relativeFilePath path of the Java source file relative to the repository root
     * @return list of method ranges found in the file
     */
    public List<MethodRange> extractMethods(String sourceCode, Path relativeFilePath) {
        CompilationUnit compilationUnit = parseCompilationUnit(sourceCode, relativeFilePath.toString());
        return extractMethodsFromCompilationUnit(compilationUnit, relativeFilePath);
    }

    private CompilationUnit parseCompilationUnit(String sourceCode, String sourceName) {
        ParseResult<CompilationUnit> parseResult = javaParser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(sourceCode));
        if(parseResult.isSuccessful() && parseResult.getResult().isPresent()){
            return parseResult.getResult().get();
        }
        throw new IllegalArgumentException("Cannot parse Java source: " + sourceName + " \n " + parseResult.getProblems());
    }

    private List<MethodRange> extractMethodsFromCompilationUnit(CompilationUnit compilationUnit, Path path) {
        String packageName = compilationUnit.getPackageDeclaration().map(NodeWithName::getNameAsString).orElse("");
        List<MethodRange> methodRanges = new ArrayList<>();
        for(MethodDeclaration methodDeclaration : compilationUnit.findAll(MethodDeclaration.class)) {
            methodDeclaration.getRange().ifPresent(range -> {
                MethodId methodId = new MethodId(packageName, resolveClassName(methodDeclaration), methodDeclaration.getNameAsString(), extractParametersTypes(methodDeclaration.getParameters()));
                MethodRange methodRange = new MethodRange(methodId, path, range.begin.line, range.end.line);
                methodRanges.add(methodRange);
            });
        }
        for(ConstructorDeclaration constructorDeclaration : compilationUnit.findAll(ConstructorDeclaration.class)) {
            constructorDeclaration.getRange().ifPresent(range -> {
                MethodId methodId = new MethodId(packageName, resolveClassName(constructorDeclaration), constructorDeclaration.getNameAsString(), extractParametersTypes(constructorDeclaration.getParameters()));
                MethodRange methodRange = new MethodRange(methodId, path, range.begin.line, range.end.line);
                methodRanges.add(methodRange);
            });
        }
        for(CompactConstructorDeclaration compactConstructorDeclaration : compilationUnit.findAll(CompactConstructorDeclaration.class)) {
            compactConstructorDeclaration.getRange().ifPresent(range -> {
                MethodId methodId = new MethodId(packageName, resolveClassName(compactConstructorDeclaration), compactConstructorDeclaration.getNameAsString(), List.of());
                MethodRange methodRange = new MethodRange(methodId, path, range.begin.line, range.end.line);
                methodRanges.add(methodRange);
            });
        }
        return methodRanges;
    }


    private String resolveClassName(Node node) {
        List<String> classNames = new ArrayList<>();
        Node currentNode = node;
        while(currentNode.getParentNode().isPresent()){
            currentNode = currentNode.getParentNode().get();

            switch (currentNode) {
                case ClassOrInterfaceDeclaration classDeclaration -> classNames.add(classDeclaration.getNameAsString());
                case EnumDeclaration enumDeclaration -> classNames.add(enumDeclaration.getNameAsString());
                case RecordDeclaration recordDeclaration -> classNames.add(recordDeclaration.getNameAsString());
                default -> {
                }
            }
        }
        Collections.reverse(classNames);
        return String.join(".", classNames);
    }

    private List<String> extractParametersTypes(NodeList<Parameter> parameters) {
        List<String> parameterTypes = new ArrayList<>();
        for(Parameter parameter : parameters){ parameterTypes.add(parameter.getType().asString());}
        return parameterTypes;
    }
}
