package it.unicam.tcpimpact.priority;

import it.unicam.tcpimpact.graph.model.ImpactGraph;
import it.unicam.tcpimpact.graph.model.MethodNode;
import it.unicam.tcpimpact.graph.model.MethodNodeType;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TestPriorityRanker {

    public List<RankedTest> rank(ImpactGraph graph) {
        AtomicInteger rank = new AtomicInteger(1);
        return graph.nodes()
                .stream()
                .filter(node -> node.nodeType() == MethodNodeType.TEST_METHOD || node.isTestMethod())
                .sorted(Comparator
                        .comparingDouble(MethodNode::propagatedRiskScore)
                        .reversed()
                        .thenComparing(this::methodId))
                .map(node -> new RankedTest(
                        rank.getAndIncrement(),
                        defects4jTestId(node),
                        methodId(node),
                        node.propagatedRiskScore()
                ))
                .toList();
    }

    private String methodId(MethodNode node) {
        if(node.fullSignature() != null && !node.fullSignature().isBlank()){
            return node.fullSignature();
        }
        return node.id().toString();
    }

    private String defects4jTestId(MethodNode node) {
        String className = node.className();
        if(node.packageName() != null && !node.packageName().isBlank() && !className.startsWith(node.packageName() + ".")){
            className = node.packageName() + "." + className;
        }
        return className + "::" + node.methodName();
    }

    public record RankedTest(int rank, String testId, String methodId, double score) {
    }
}
