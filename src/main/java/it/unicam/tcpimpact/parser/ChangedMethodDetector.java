package it.unicam.tcpimpact.parser;
import it.unicam.tcpimpact.git.ChangedJavaFile;
import it.unicam.tcpimpact.model.ChangedMethod;
import it.unicam.tcpimpact.model.MethodRange;
import java.util.ArrayList;
import java.util.List;

public class ChangedMethodDetector {

    /**
     * Detects changed methods by intersecting changed lines with method ranges.
     *
     * @param changedJavaFile changed Java file with changed line numbers
     * @param methodRanges methods declared in the same Java file
     * @return list of methods affected by the change
     */

    public List<ChangedMethod> detectChangedMethods(ChangedJavaFile changedJavaFile, List<MethodRange> methodRanges) {
        List<ChangedMethod> changedMethods = new ArrayList<ChangedMethod>();

        for(MethodRange methodRange : methodRanges) {
            List<Integer> changedLinesInsideMethod = findChangedLinesInsideMethod(changedJavaFile.changedLines(), methodRange);
            if(!changedLinesInsideMethod.isEmpty()) {
                ChangedMethod changedMethod = new ChangedMethod(methodRange, changedLinesInsideMethod);
                changedMethods.add(changedMethod);
            }
        }
        return changedMethods;
    }

    private List<Integer> findChangedLinesInsideMethod(List<Integer> changedLines, MethodRange methodRange) {
        List<Integer> result = new ArrayList<>();
        for(Integer changedLine : changedLines) {
            if(isLineInsideMethod(changedLine, methodRange)){
                result.add(changedLine);
            }
        }
        return result;
    }

    private boolean isLineInsideMethod(Integer line, MethodRange methodRange) {
        return line >= methodRange.start() && line <= methodRange.end();
    }
}
