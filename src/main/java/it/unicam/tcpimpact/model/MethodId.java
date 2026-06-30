package it.unicam.tcpimpact.model;
import java.util.List;
import java.util.StringJoiner;

/**
 * Identifies a Java method through its pacakge, class, name and signature.
 *
 * @param packageName package containing the class
 * @param className class declaring the method
 * @param methodName method name
 * @param parameterTypes method parameter types
 */

public record MethodId(String packageName, String className, String methodName, List<String> parameterTypes) {

    @Override
    public String toString(){
        StringJoiner parameters = new StringJoiner(",");
        for(String parameterType : parameterTypes){ parameters.add(parameterType); }
        String result = className + "." + methodName + "(" + parameters + ")";
        if(packageName == null || packageName.isBlank()){ return result; }
        return packageName + "." + result;
    }
}
