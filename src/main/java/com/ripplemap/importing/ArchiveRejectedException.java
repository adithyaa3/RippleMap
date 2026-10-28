package com.ripplemap.importing;

/** An uploaded archive violates a safety rule or limit. The message is safe to show to the user. */
public class ArchiveRejectedException extends Exception {

    public enum Reason {
        INVALID_ARCHIVE,
        EMPTY_ARCHIVE,
        NO_JAVA_FILES,
        TOO_MANY_ENTRIES,
        TOO_MANY_JAVA_FILES,
        ENTRY_TOO_LARGE,
        EXPANDED_TOO_LARGE,
        SUSPICIOUS_COMPRESSION,
        PATH_TRAVERSAL,
        ABSOLUTE_PATH,
        INVALID_PATH,
        PATH_TOO_DEEP,
        PATH_TOO_LONG,
        DUPLICATE_ENTRY,
        UPLOAD_TOO_LARGE
    }

    private final Reason reason;

    public ArchiveRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
