package com.ripplemap.analysis;

/** The input exceeds a configured analysis limit; the analysis is not started or is aborted. */
public class AnalysisLimitException extends RuntimeException {

    public AnalysisLimitException(String message) {
        super(message);
    }
}
