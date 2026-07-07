package it.unicam.tcpimpact.graph.model;
import it.unicam.tcpimpact.model.MethodId;
import java.util.List;

public record MethodNode(
        MethodId id,
        MethodNodeType nodeType,
        String packageName,
        String className,
        String methodName,
        String fullSignature,
        String returnType,
        String filePath,
        int startLine,
        int endLine,
        String visibility,
        String methodKind,
        boolean isStatic,
        boolean isAbstract,
        boolean isInInterface,
        boolean isConstructor,
        boolean isTestMethod,
        ChangeStatus changeStatus,
        List<Integer> changedLines,
        double riskValue,
        double initialRiskValue,
        double propagatedRiskScore,
        List<String> externalCalls
) {

    public MethodNode {
        // normalization of fields
        changedLines = changedLines == null ? List.of() : List.copyOf(changedLines);
        externalCalls = externalCalls == null ? List.of() : List.copyOf(externalCalls);
    }

    public double initialRiskScore() {
        return initialRiskValue;
    }

    public MethodNode withChange(ChangeStatus status, List<Integer> lines) {
        return new MethodNode(
                id,
                nodeType,
                packageName,
                className,
                methodName,
                fullSignature,
                returnType,
                filePath,
                startLine,
                endLine,
                visibility,
                methodKind,
                isStatic,
                isAbstract,
                isInInterface,
                isConstructor,
                isTestMethod,
                status,
                lines,
                riskValue,
                initialRiskValue,
                propagatedRiskScore,
                externalCalls
        );
    }

    /**
     * Updates the entity risk and the value used to seed graph propagation.
     */
    public MethodNode withRiskValues(double riskValue, double initialRiskValue) {
        return new MethodNode(
                id,
                nodeType,
                packageName,
                className,
                methodName,
                fullSignature,
                returnType,
                filePath,
                startLine,
                endLine,
                visibility,
                methodKind,
                isStatic,
                isAbstract,
                isInInterface,
                isConstructor,
                isTestMethod,
                changeStatus,
                changedLines,
                riskValue,
                initialRiskValue,
                propagatedRiskScore,
                externalCalls
        );
    }

    public MethodNode withPropagatedRiskScore(double score) {
        return new MethodNode(
                id,
                nodeType,
                packageName,
                className,
                methodName,
                fullSignature,
                returnType,
                filePath,
                startLine,
                endLine,
                visibility,
                methodKind,
                isStatic,
                isAbstract,
                isInInterface,
                isConstructor,
                isTestMethod,
                changeStatus,
                changedLines,
                riskValue,
                initialRiskValue,
                score,
                externalCalls
        );
    }

    public MethodNode withExternalCalls(List<String> calls) {
        return new MethodNode(
                id,
                nodeType,
                packageName,
                className,
                methodName,
                fullSignature,
                returnType,
                filePath,
                startLine,
                endLine,
                visibility,
                methodKind,
                isStatic,
                isAbstract,
                isInInterface,
                isConstructor,
                isTestMethod,
                changeStatus,
                changedLines,
                riskValue,
                initialRiskValue,
                propagatedRiskScore,
                calls
        );
    }
}
