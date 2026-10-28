package com.acme.model;

public interface Shape {
    double area();

    default String describe() {
        return "shape with area " + area();
    }
}
