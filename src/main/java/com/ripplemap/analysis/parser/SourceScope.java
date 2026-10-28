package com.ripplemap.analysis.parser;

/** Classification of a discovered Java file. */
public enum SourceScope {
    /** Production source. Always analyzed. */
    MAIN,
    /** Test source; analyzed only when tests are included. */
    TEST,
    /** Generated build output (e.g. target/generated-sources); never analyzed. */
    BUILD_OUTPUT
}
