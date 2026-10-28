package com.ripplemap.analysis.resolution;

/**
 * Bounds the time one symbol-solver invocation may take. JavaParser's solver cannot be interrupted, but every type
 * lookup it performs goes through {@link ProjectTypeSolver}, which calls {@link #check()}: when the per-reference
 * budget is exhausted a {@link BudgetExceededException} unwinds the solver and the reference falls back to the
 * documented inference rules. The same check also enforces the analysis deadline and cancellation.
 */
public final class ResolutionBudget {

    /** Thrown inside the solver when the current reference exceeded its budget. */
    public static final class BudgetExceededException extends RuntimeException {
        public BudgetExceededException() {
            super("Symbol resolution exceeded its time budget", null, false, false);
        }
    }

    private final long perReferenceNanos;
    private final Runnable analysisCheckpoint;
    private long deadline;
    private boolean active;
    private int checks;
    private long exceeded;
    private long totalNanos;
    private long maxNanos;
    private long started;

    /**
     * @param perReferenceNanos  budget of one invocation; {@code <= 0} disables the per-reference limit
     * @param analysisCheckpoint checks cancellation and the analysis deadline (throws to abort)
     */
    public ResolutionBudget(long perReferenceNanos, Runnable analysisCheckpoint) {
        this.perReferenceNanos = perReferenceNanos;
        this.analysisCheckpoint = analysisCheckpoint;
    }

    public void begin() {
        started = System.nanoTime();
        deadline = perReferenceNanos > 0 ? started + perReferenceNanos : Long.MAX_VALUE;
        active = true;
    }

    public void end() {
        if (active) {
            long elapsed = System.nanoTime() - started;
            totalNanos += elapsed;
            maxNanos = Math.max(maxNanos, elapsed);
        }
        active = false;
    }

    /** Called from inside the solver on every type lookup. */
    public void check() {
        if (!active) {
            return;
        }
        if ((++checks & 0x3FF) == 0) {
            analysisCheckpoint.run();
        }
        if (System.nanoTime() > deadline) {
            exceeded++;
            active = false;
            totalNanos += System.nanoTime() - started;
            throw new BudgetExceededException();
        }
    }

    public long exceededCount() {
        return exceeded;
    }

    public long totalMillis() {
        return totalNanos / 1_000_000;
    }

    public long maxMillis() {
        return maxNanos / 1_000_000;
    }
}
