package com.ripplemap.analysis.model;

/**
 * File counts of an analysis run.
 *
 * @param discovered      all regular .java files found below the root
 * @param inScope         files selected for analysis (discovered minus excluded)
 * @param excludedTests   test files skipped because tests were not included
 * @param excludedGenerated generated build-output files skipped
 * @param parsed          in-scope files parsed successfully
 * @param failed          in-scope files that could not be read or parsed
 * @param skippedLinks    symbolic links (and unreadable entries) skipped during discovery
 */
public record FileCounts(int discovered, int inScope, int excludedTests, int excludedGenerated, int parsed,
                         int failed, int skippedLinks) {
}
