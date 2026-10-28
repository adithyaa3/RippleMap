package com.ripplemap.analysis.model;

/** The two dependency graphs produced by an analysis. */
public enum GraphLayer {
    /** Classes, interfaces, enums, records and annotation types. */
    TYPE,
    /** Methods and constructors declared in source types. */
    METHOD
}
