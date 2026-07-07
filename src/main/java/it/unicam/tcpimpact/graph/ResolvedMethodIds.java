package it.unicam.tcpimpact.graph;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import it.unicam.tcpimpact.model.MethodId;
import java.util.List;

/**
 * Converts JavaParser resolved declarations to the shared {@link MethodId} representation used by the rest of the application.
 */
final class ResolvedMethodIds {

    private ResolvedMethodIds() {
    }

    /**
     * Builds a stable identifier for a resolved method declaration.
     *
     * @param resolved resolved JavaParser method declaration
     * @return project method identifier
     */
    static MethodId from(ResolvedMethodDeclaration resolved) {
        String packageName = resolved.getPackageName();
        String className = className(packageName, resolved.declaringType().getQualifiedName());
        List<String> parameterTypes = resolved.formalParameterTypes()
                .stream()
                .map(type -> type.describe())
                .toList();
        return new MethodId(packageName, className, resolved.getName(), parameterTypes, resolved.getReturnType().describe());
    }

    static MethodId from(ResolvedConstructorDeclaration resolved) {
        String packageName = resolved.declaringType().getPackageName();
        String className = className(packageName, resolved.declaringType().getQualifiedName());
        List<String> parameterTypes = resolved.formalParameterTypes()
                .stream()
                .map(type -> type.describe())
                .toList();
        return new MethodId(packageName, className, MethodId.CONSTRUCTOR_NAME, parameterTypes, "");
    }

    private static String className(String packageName, String qualifiedTypeName) {
        if (packageName == null || packageName.isBlank()) {
            return qualifiedTypeName;
        }
        String packagePrefix = packageName + ".";
        if (qualifiedTypeName.startsWith(packagePrefix)) {
            return qualifiedTypeName.substring(packagePrefix.length());
        }
        return qualifiedTypeName;
    }
}
