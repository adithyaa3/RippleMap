package com.ripplemap.entity;

public enum SourceType {
    /** Sources extracted from an uploaded ZIP archive (one stored copy per imported version). */
    ZIP_UPLOAD,
    /** Sources read from an authorized local directory at scan time. */
    LOCAL_DIRECTORY
}
