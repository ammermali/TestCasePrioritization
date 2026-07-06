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
        double initialRiskScore,
        double propagatedRiskScore,
        List<String> externalCalls
) {

    public MethodNode {
        // normalization of fields
        changedLines = changedLines == null ? List.of() : List.copyOf(changedLines);
        externalCalls = externalCalls == null ? List.of() : List.copyOf(externalCalls);
    }

    /**
     * Returns a non-immutable copy of the node.
     *
     * @param status
     * @param lines
     * @param initialRisk
     * @return copy
     */
    public MethodNode withChange(ChangeStatus status, List<Integer> lines, double initialRisk) {
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
                initialRisk,
                propagatedRiskScore,
                externalCalls
        );
    }

    /**
     * Returns a non-immutable copy of the node with the risk score changed.
     *
     * @param score new score
     * @return copy
     */
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
                initialRiskScore,
                score,
                externalCalls
        );
    }

    /**
     * Returns a copy with updated external call metadata.
     *
     * @param calls
     * @return copy
     */
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
                initialRiskScore,
                propagatedRiskScore,
                calls
        );
    }
}
