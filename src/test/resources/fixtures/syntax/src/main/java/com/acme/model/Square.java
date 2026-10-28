package com.acme.model;

public class Square extends AbstractShape {
    private final double side;

    public Square(double side) {
        super("square");
        this.side = side;
    }

    public Square() {
        this(1.0);
    }

    @Override
    public double area() {
        return side * side;
    }
}
