package com.acme.modern;

public record Point(int x, int y) {
    public Point {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("negative");
        }
    }

    public double distance(Point other) {
        int dx = x - other.x();
        int dy = y - other.y();
        return Math.sqrt(dx * dx + dy * dy);
    }
}
