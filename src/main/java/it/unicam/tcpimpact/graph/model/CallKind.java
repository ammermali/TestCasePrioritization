package it.unicam.tcpimpact.graph.model;

public enum CallKind {
    NORMAL,
    CONSTRUCTOR,
    SUPER,
    PRIVATE,
    METHOD_REFERENCE, // ClassName::MethodName
    LAMBDA, // lambda function
    EXTERNAL, // towards an external library
    UNRESOLVED
}
