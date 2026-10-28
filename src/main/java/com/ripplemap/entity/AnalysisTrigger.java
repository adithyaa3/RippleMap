package com.ripplemap.entity;

public enum AnalysisTrigger {
    /** First analysis after creating the project. */
    IMPORT,
    /** Analysis of a newly uploaded version of a ZIP project. */
    NEW_VERSION,
    /** Re-analysis of an already stored version (sources unchanged). */
    REANALYZE,
    /** Rescan of the current contents of a local directory. */
    RESCAN
}
