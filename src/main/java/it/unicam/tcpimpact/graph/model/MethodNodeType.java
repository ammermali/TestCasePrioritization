package it.unicam.tcpimpact.graph.model;

public enum MethodNodeType {
    METHOD, // normal production method
    TEST_METHOD, // @Test method
    CONSTRUCTOR_METHOD, // constructor treated as a method
    ABSTRACT_METHOD, // abstract method declaration
    INTERFACE_METHOD, // interface method without body
    DEFAULT_INTERFACE_METHOD, // interface method with body
    TEST_SUPPORT_METHOD // non-test method under src/test/java TODO: remove?
}
