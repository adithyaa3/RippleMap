package com.ripplemap.analysis.model;

/**
 * Counts of reference resolution attempts. A reference is one source construct that may denote a project
 * declaration (a type name, call, field access, annotation, ...). An object creation contributes a type
 * reference and, at method level, a constructor reference.
 */
public final class ReferenceStatistics {

    /** Linked to a project declaration by the symbol solver. */
    public long resolved;
    /** Linked to a project declaration by a documented inference rule. */
    public long inferred;
    /** Could not be linked and may hide a project dependency. */
    public long unresolved;
    /** Could not be linked but most likely denote external library symbols. */
    public long likelyExternal;
    /** Denote JDK/library declarations; not project dependencies. */
    public long external;
    /** Refer to the same type (self-references) or to local/anonymous classes; not graph edges. */
    public long internal;
    /** Symbol-solver invocations that threw; the reference then went through the fallback rules. */
    public long solverFailures;
    /** Solver invocations stopped because they exceeded the per-reference time budget (also in solverFailures). */
    public long budgetExceeded;

    public long linked() {
        return resolved + inferred;
    }

    public long resolved() {
        return resolved;
    }

    public long inferred() {
        return inferred;
    }

    public long unresolved() {
        return unresolved;
    }

    public long likelyExternal() {
        return likelyExternal;
    }

    public long external() {
        return external;
    }

    public long internal() {
        return internal;
    }

    public long solverFailures() {
        return solverFailures;
    }

    public long budgetExceeded() {
        return budgetExceeded;
    }
}
