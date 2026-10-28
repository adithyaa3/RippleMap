package com.ripplemap.analysis.resolution;

import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes symbol resolution across concurrent analyses.
 *
 * <p>JavaParser's symbol solver keeps process-wide static state: {@code JavaParserFacade} caches one facade
 * per type solver in a static map whose values strongly reference their keys (so entries are never
 * collected), and some inference caches are static unsynchronized maps. Running resolution for one analysis
 * at a time and clearing the facade registry afterwards keeps concurrent analyses correct and bounds memory.
 */
public final class SymbolSolverGate {

    private static final ReentrantLock LOCK = new ReentrantLock(true);

    private SymbolSolverGate() {
    }

    public interface ResolutionWork<T, E extends Exception> {
        T run() throws E;
    }

    public static <T, E extends Exception> T runExclusively(ResolutionWork<T, E> work) throws E, InterruptedException {
        LOCK.lockInterruptibly();
        try {
            return work.run();
        } finally {
            try {
                JavaParserFacade.clearInstances();
            } finally {
                LOCK.unlock();
            }
        }
    }
}
