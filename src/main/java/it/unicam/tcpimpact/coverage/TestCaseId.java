package it.unicam.tcpimpact.coverage;

public record TestCaseId(String className, String methodName) {

    public String toGradleFilter() {
        return className + "." + methodName;
    }

    public String toSafeFileName() {
        return (className + "_" + methodName).replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @Override
    public String toString() {
        return toGradleFilter();
    }
}