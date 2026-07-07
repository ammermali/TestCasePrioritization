package it.unicam.tcpimpact.model;
import java.util.List;
import java.util.StringJoiner;

/**
 * Identifies a Java method through its package, class, name and signature.
 *
 * @param packageName package containing the class
 * @param className class declaring the method
 * @param methodName method name
 * @param parameterTypes method parameter types
 * @param returnType method return type or blank for constructors
 */

public record MethodId(String packageName, String className, String methodName, List<String> parameterTypes, String returnType) {

    // package.Class#<init>(params) for constructor
    // package.Class#method(params):returnType for other methods
    public static final String CONSTRUCTOR_NAME = "<init>";

    public MethodId(String packageName, String className, String methodName, List<String> parameterTypes) {
        this(packageName, className, methodName, parameterTypes, "");
    }

    @Override
    public String toString(){
        StringJoiner parameters = new StringJoiner(",");
        for(String parameterType : parameterTypes){ parameters.add(parameterType); }
        String result = className + "#" + methodName + "(" + parameters + ")";
        if(returnType != null && !returnType.isBlank() && !CONSTRUCTOR_NAME.equals(methodName)){
            result = result + ":" + returnType;
        }
        if(packageName == null || packageName.isBlank()){ return result; }
        return packageName + "." + result;
    }
}
