package com.ripplemap.importing;

import com.ripplemap.importing.ArchiveRejectedException.Reason;
import com.ripplemap.util.TextSanitizer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Extracts the {@code .java} files of an untrusted ZIP archive into an empty directory.
 *
 * <p>Protections: entry names are validated before use (no absolute paths, drive letters, ':' or NUL,
 * no {@code ..} segments, no control characters or Windows device names, bounded depth and length) and
 * case-insensitive duplicates are rejected. Every target path is re-checked to stay inside the destination.
 * Only {@code .java} entries are decompressed; their size is enforced while streaming (declared sizes are not
 * trusted), per file and in total, and extreme compression ratios are rejected as likely ZIP bombs.
 * Files are created with {@code CREATE_NEW}; symbolic-link entries are written as plain files containing the
 * link text, so no link is ever created or followed. Nothing extracted is ever executed.
 *
 * <p>On any rejection the caller is expected to delete the destination directory.
 */
public final class SafeZipExtractor {

    private static final Pattern DEVICE_NAME = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");
    private static final int RATIO_MIN_BYTES = 64 * 1024;
    private static final int BUFFER = 16 * 1024;

    public record Limits(long maxExpandedBytes, long maxEntryBytes, int maxEntries, int maxJavaFiles,
                         int maxPathDepth, int maxPathLength, int maxCompressionRatio) {
    }

    /**
     * @param entryCount     all entries in the archive (files and directories)
     * @param javaFileCount  extracted .java files
     * @param extractedBytes total bytes written
     * @param skippedEntries non-Java file entries that were not extracted
     */
    public record Result(int entryCount, int javaFileCount, long extractedBytes, int skippedEntries,
                         List<String> javaFiles) {
    }

    private SafeZipExtractor() {
    }

    public static Result extract(Path archive, Path destination, Limits limits)
            throws ArchiveRejectedException, IOException {
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Destination must be an existing directory");
        }
        Path root = destination.toRealPath();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            int total = zip.size();
            if (total == 0) {
                throw new ArchiveRejectedException(Reason.EMPTY_ARCHIVE, "The archive is empty");
            }
            if (total > limits.maxEntries()) {
                throw new ArchiveRejectedException(Reason.TOO_MANY_ENTRIES,
                        "The archive has " + total + " entries; at most " + limits.maxEntries() + " are allowed");
            }
            Set<String> seen = new HashSet<>();
            List<String> javaFiles = new ArrayList<>();
            long extracted = 0;
            int skipped = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry;
                try {
                    entry = entries.nextElement();
                } catch (IllegalArgumentException e) {
                    throw new ArchiveRejectedException(Reason.INVALID_ARCHIVE,
                            "The archive contains an entry name that is not valid UTF-8");
                }
                String normalized = validateName(entry.getName(), limits);
                if (normalized.isEmpty()) {
                    continue;
                }
                if (!seen.add(normalized.toLowerCase(Locale.ROOT))) {
                    throw new ArchiveRejectedException(Reason.DUPLICATE_ENTRY,
                            "The archive contains the entry '" + display(normalized) + "' more than once");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (!normalized.toLowerCase(Locale.ROOT).endsWith(".java")) {
                    skipped++;
                    continue;
                }
                if (javaFiles.size() >= limits.maxJavaFiles()) {
                    throw new ArchiveRejectedException(Reason.TOO_MANY_JAVA_FILES,
                            "The archive contains more than " + limits.maxJavaFiles() + " Java files");
                }
                if (entry.getSize() > limits.maxEntryBytes()) {
                    throw new ArchiveRejectedException(Reason.ENTRY_TOO_LARGE, "'" + display(normalized)
                            + "' is larger than the per-file limit of " + limits.maxEntryBytes() + " bytes");
                }
                Path target = root.resolve(normalized).normalize();
                if (!target.startsWith(root) || target.equals(root)) {
                    throw new ArchiveRejectedException(Reason.PATH_TRAVERSAL,
                            "The entry '" + display(normalized) + "' would be extracted outside the import directory");
                }
                Path parent = target.getParent();
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    throw new ArchiveRejectedException(Reason.INVALID_PATH,
                            "The entry '" + display(normalized) + "' conflicts with another entry");
                }
                if (!parent.toRealPath().startsWith(root)) {
                    throw new ArchiveRejectedException(Reason.PATH_TRAVERSAL,
                            "The entry '" + display(normalized) + "' would be extracted outside the import directory");
                }
                long written;
                try (InputStream in = zip.getInputStream(entry)) {
                    written = copyBounded(in, target, limits.maxEntryBytes(), limits.maxExpandedBytes() - extracted,
                            normalized, limits);
                }
                long compressed = entry.getCompressedSize();
                if (written > RATIO_MIN_BYTES && compressed > 0 && written / compressed > limits.maxCompressionRatio()) {
                    throw new ArchiveRejectedException(Reason.SUSPICIOUS_COMPRESSION, "'" + display(normalized)
                            + "' has a suspicious compression ratio (possible ZIP bomb)");
                }
                extracted += written;
                javaFiles.add(normalized);
            }
            if (javaFiles.isEmpty()) {
                throw new ArchiveRejectedException(Reason.NO_JAVA_FILES, "The archive contains no .java files");
            }
            return new Result(total, javaFiles.size(), extracted, skipped, List.copyOf(javaFiles));
        } catch (ZipException e) {
            throw new ArchiveRejectedException(Reason.INVALID_ARCHIVE, "The file is not a valid ZIP archive");
        }
    }

    /**
     * Normalizes an entry name to a relative '/'-separated path, or returns "" for entries that denote the root.
     * Rejects anything that could escape the destination or is ambiguous on some file system.
     */
    static String validateName(String raw, Limits limits) throws ArchiveRejectedException {
        if (raw == null || raw.isEmpty()) {
            throw new ArchiveRejectedException(Reason.INVALID_PATH, "The archive contains an entry without a name");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new ArchiveRejectedException(Reason.INVALID_PATH,
                        "The archive contains an entry name with control characters");
            }
        }
        String name = raw.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new ArchiveRejectedException(Reason.ABSOLUTE_PATH,
                    "The entry '" + display(name) + "' uses an absolute path");
        }
        if (name.indexOf(':') >= 0) {
            throw new ArchiveRejectedException(Reason.INVALID_PATH,
                    "The entry '" + display(name) + "' contains ':' which is not allowed");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new ArchiveRejectedException(Reason.PATH_TRAVERSAL,
                        "The entry '" + display(name) + "' contains a parent-directory reference");
            }
            if (DEVICE_NAME.matcher(segment).matches() || segment.endsWith(".") || segment.endsWith(" ")) {
                throw new ArchiveRejectedException(Reason.INVALID_PATH,
                        "The entry '" + display(name) + "' uses a reserved or ambiguous file name");
            }
            segments.add(segment);
        }
        if (segments.size() > limits.maxPathDepth()) {
            throw new ArchiveRejectedException(Reason.PATH_TOO_DEEP,
                    "The entry '" + display(name) + "' is nested deeper than " + limits.maxPathDepth() + " levels");
        }
        String normalized = String.join("/", segments);
        if (normalized.length() > limits.maxPathLength()) {
            throw new ArchiveRejectedException(Reason.PATH_TOO_LONG,
                    "An entry path is longer than " + limits.maxPathLength() + " characters");
        }
        return normalized;
    }

    private static long copyBounded(InputStream in, Path target, long maxEntry, long remainingTotal, String name,
                                    Limits limits) throws IOException, ArchiveRejectedException {
        long written = 0;
        byte[] buffer = new byte[BUFFER];
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                written += read;
                if (written > maxEntry) {
                    throw new ArchiveRejectedException(Reason.ENTRY_TOO_LARGE, "'" + display(name)
                            + "' is larger than the per-file limit of " + maxEntry + " bytes");
                }
                if (written > remainingTotal) {
                    throw new ArchiveRejectedException(Reason.EXPANDED_TOO_LARGE,
                            "The extracted Java sources exceed the limit of " + limits.maxExpandedBytes() + " bytes");
                }
                out.write(buffer, 0, read);
            }
        } catch (ZipException e) {
            throw new ArchiveRejectedException(Reason.INVALID_ARCHIVE,
                    "The entry '" + display(name) + "' could not be decompressed (corrupt or encrypted)");
        }
        return written;
    }

    private static String display(String name) {
        return TextSanitizer.singleLine(name, 160);
    }
}
