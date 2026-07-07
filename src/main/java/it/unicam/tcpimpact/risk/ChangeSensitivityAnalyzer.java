package it.unicam.tcpimpact.risk;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;

/**
 * Assigns an interpretable sensitivity score to changed lines inside a method.
 * In the future it might be expanded with actual semantic/embedded-based implementation.
 */
public class ChangeSensitivityAnalyzer {

    /**
     * Computes the highest structural sensitivity touched by the changed lines.
     *
     * @param declaration method or constructor declaration from JavaParser
     * @param changedLines changed line numbers inside the declaration
     * @return normalized sensitivity in {@code [0, 1]}
     */
    public double sensitivity(Node declaration, List<Integer> changedLines) {
        if(declaration == null || changedLines == null || changedLines.isEmpty()){
            return 0.0;
        }
        double score = 0.10;
        List<Node> nodes = declaration.findAll(Node.class);
        for(Integer changedLine : changedLines){
            if(changedLine == null){
                continue;
            }
            if(touchesSignature(declaration, changedLine)){
                score = Math.max(score, 1.0);
            }
            for(Node node : nodes){
                if(touchesLine(node, changedLine)){
                    score = Math.max(score, score(node));
                }
            }
        }
        return EntityRiskMetrics.clamp(score);
    }

    private boolean touchesSignature(Node declaration, int line) {
        if(declaration instanceof MethodDeclaration method){
            return line >= beginLine(method) && line <= bodyBeginLine(method);
        }
        if(declaration instanceof ConstructorDeclaration constructor){
            return line >= beginLine(constructor) && line <= constructor.getBody().getBegin().map(position -> position.line).orElse(beginLine(constructor));
        }
        if(declaration instanceof CompactConstructorDeclaration constructor){
            return line >= beginLine(constructor) && line <= constructor.getBody().getBegin().map(position -> position.line).orElse(beginLine(constructor));
        }
        return false;
    }

    private int bodyBeginLine(MethodDeclaration method) {
        return method.getBody()
                .flatMap(Node::getBegin)
                .map(position -> position.line)
                .orElse(beginLine(method));
    }

    private int beginLine(Node node) {
        return node.getBegin().map(position -> position.line).orElse(Integer.MAX_VALUE);
    }

    private boolean touchesLine(Node node, int line) {
        return node.getRange()
                .map(range -> line >= range.begin.line && line <= range.end.line)
                .orElse(false);
    }

    private double score(Node node) {
        if(node instanceof Parameter){
            return 1.0;
        }
        if(node instanceof ReturnStmt || node instanceof ThrowStmt){
            return 0.85;
        }
        if(node instanceof IfStmt
                || node instanceof SwitchStmt
                || node instanceof ForStmt
                || node instanceof ForEachStmt
                || node instanceof WhileStmt
                || node instanceof DoStmt
                || node instanceof TryStmt
                || node instanceof ConditionalExpr){
            return 0.80;
        }
        if(node instanceof AssignExpr || node instanceof UnaryExpr){
            return 0.70;
        }
        if(node instanceof ObjectCreationExpr){
            return 0.65;
        }
        if(node instanceof MethodCallExpr || node instanceof MethodReferenceExpr){
            return 0.60;
        }
        if(node instanceof VariableDeclarator){
            return 0.45;
        }
        if(node instanceof ExpressionStmt){
            return 0.35;
        }
        return 0.10;
    }
}
