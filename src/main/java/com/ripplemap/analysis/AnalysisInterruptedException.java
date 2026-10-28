package com.ripplemap.analysis;

/** Thrown at a cooperative checkpoint when an analysis is cancelled or exceeds its deadline. */
public class AnalysisInterruptedException extends RuntimeException {

    public enum Reason {
        CANCELLED,
        DEADLINE_EXCEEDED
    }

    private final Reason reason;

    public AnalysisInterruptedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
