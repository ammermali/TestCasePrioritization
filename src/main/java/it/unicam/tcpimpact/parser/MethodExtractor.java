package it.unicam.tcpimpact.parser;


import com.github.javaparser.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import it.unicam.tcpimpact.model.MethodId;
import it.unicam.tcpimpact.model.MethodRange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MethodExtractor {

    private final JavaParser javaParser;

    public MethodExtractor(){
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setLanguageLevel(ParserConfiguration.LanguageLevel.CURRENT);
        this.javaParser = new JavaParser(parserConfiguration);
    }

    /**
     * Extracts all methods and constructors declared in the given Java file.
     *
     * @param absoluteFilePath absolute path of the Java source file
     * @param relativeFilePath path of the Java source file relative to the repository root
     * @return list of method ranges found in the file
     * @throws IOException if the file cannot be read
     */
    public List<MethodRange> extractMethods(Path absoluteFilePath, Path relativeFilePath) throws IOException {
        CompilationUnit compilationUnit = parseCompilationUnit(absoluteFilePath);
        String packageName = compilationUnit.getPackageDeclaration().map(NodeWithName::getNameAsString).orElse("");
        List<MethodRange> methodRanges = new ArrayList<>();

        for(MethodDeclaration methodDeclaration : compilationUnit.findAll(MethodDeclaration.class)){
            methodDeclaration.getRange().ifPresent(range -> {
                MethodId methodId = new MethodId(packageName, resolveClassName(methodDeclaration), methodDeclaration.getNameAsString(), extractParametersTypes(methodDeclaration.getParameters()));
                MethodRange methodRange = new MethodRange(methodId, relativeFilePath, range.begin.line, range.end.line);
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

            if(currentNode instanceof ClassOrInterfaceDeclaration classDeclaration){
                classNames.add(classDeclaration.getNameAsString());
            }else if(currentNode instanceof EnumDeclaration enumDeclaration){
                classNames.add(enumDeclaration.getNameAsString());
            }else if (currentNode instanceof RecordDeclaration recordDeclaration){
                classNames.add(recordDeclaration.getNameAsString());
            }
        }
        Collections.reverse(classNames);
        return String.join(".", classNames);
    }

    private List<String> extractParametersTypes(NodeList<Parameter> parameters) {
        List<String> parameterTypes = new ArrayList<>();
        for(Parameter parameter : parameters){ parameterTypes.add(parameter.getNameAsString());}
        return parameterTypes;
    }

    private CompilationUnit parseCompilationUnit(Path absoluteFilePath) throws IOException {
        ParseResult<CompilationUnit> parseResult = javaParser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(absoluteFilePath));
        if(parseResult.isSuccessful() && parseResult.getResult().isPresent()){
            return parseResult.getResult().get();
        }
        throw new IllegalArgumentException("Cannot parse Java file: " + absoluteFilePath + "\n" + parseResult.getProblems());
    }

}
