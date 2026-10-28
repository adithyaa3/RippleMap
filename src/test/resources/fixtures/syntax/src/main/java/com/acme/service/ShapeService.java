package com.acme.service;

import com.acme.model.*;
import com.acme.util.Formatter;
import java.util.ArrayList;
import java.util.List;
import org.external.lib.Audit;
import org.external.lib.Bag;

public class ShapeService<T extends Shape> {
    private final List<Shape> shapes = new ArrayList<>();
    private final Audit audit;
    private Bag bag;

    public ShapeService(Audit audit) {
        this.audit = audit;
    }

    public void add(Shape shape) {
        shapes.add(shape);
    }

    public Circle createCircle(double radius) {
        Circle circle = new Circle(radius);
        add(circle);
        return circle;
    }

    public String report() {
        double total = 0;
        for (Shape shape : shapes) {
            total += shape.area();
        }
        return Formatter.format(total) + " / " + Formatter.format(total, 2);
    }

    public void audit() {
        audit.record("report");
    }

    public Formatter.Options options() {
        return new Formatter.Options();
    }

    public String formatExternal() {
        return Formatter.format(audit.text(), 3);
    }

    public String formatAmbiguous() {
        return Formatter.format(audit.value());
    }

    public void unknownReceiver() {
        bag.forEach(item -> item.area());
    }

    public T first(T fallback) {
        return fallback;
    }
}
