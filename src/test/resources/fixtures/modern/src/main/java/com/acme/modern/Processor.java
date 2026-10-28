package com.acme.modern;

@Audited("processor")
public class Processor {

    public String handle(Event event) {
        return switch (event) {
            case Created created -> created.id();
            case Deleted deleted -> deleted.id();
        };
    }

    public int limit() {
        return Constants.LIMIT;
    }

    public boolean check(Status status) {
        return status == Status.ACTIVE && status.isActive();
    }

    public Status[] all() {
        return Status.values();
    }

    public int sum(Point point) {
        return point.x() + point.y();
    }
}
