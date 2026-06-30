package it.unicam.tcpimpact.coverage;

public record TestCaseId (String className, String methodName){
    public String toGradleFilter(){
        return className + "." + methodName;
    }

    public String toSafeFileName(){
        return toGradleFilter()
                .replace(".","_")
                .replace("#","_")
                .replace("[", "_")
                .replace("]", "_");
    }

    @Override
    public String toString() {
        return toGradleFilter();
    }
}
