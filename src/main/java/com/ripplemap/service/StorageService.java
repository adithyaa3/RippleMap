package com.ripplemap.service;

import com.ripplemap.config.RippleMapProperties;
import com.ripplemap.util.FileTrees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Owns the data directory layout:
 * <pre>
 * &lt;data-dir&gt;/db/        H2 database files
 * &lt;data-dir&gt;/imports/   one directory (random UUID) per imported version, containing only .java files
 * &lt;data-dir&gt;/tmp/       uploads in progress
 * </pre>
 * Only directories created by this service are ever deleted.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final Pattern STORAGE_NAME = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Path dataDir;
    private final Path importsDir;
    private final Path tmpDir;

    public StorageService(RippleMapProperties properties) {
        this.dataDir = Paths.get(properties.getDataDir()).toAbsolutePath().normalize();
        this.importsDir = dataDir.resolve("imports");
        this.tmpDir = dataDir.resolve("tmp");
        try {
            Files.createDirectories(importsDir);
            Files.createDirectories(tmpDir);
            Files.createDirectories(dataDir.resolve("db"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data directory " + dataDir, e);
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    /** A new, empty import directory and its storage name. */
    public NewImportDir newImportDir() throws IOException {
        String name = UUID.randomUUID().toString();
        Path dir = importsDir.resolve(name);
        Files.createDirectory(dir);
        return new NewImportDir(name, dir);
    }

    public record NewImportDir(String storageName, Path path) {
    }

    /** Resolves a stored import directory; rejects names that were not produced by this service. */
    public Path importDir(String storageName) {
        if (storageName == null || !STORAGE_NAME.matcher(storageName).matches()) {
            throw new IllegalArgumentException("Invalid storage directory name");
        }
        Path dir = importsDir.resolve(storageName).normalize();
        if (!dir.getParent().equals(importsDir)) {
            throw new IllegalArgumentException("Invalid storage directory name");
        }
        return dir;
    }

    public Path newTempFile(String suffix) throws IOException {
        return Files.createTempFile(tmpDir, "upload-", suffix);
    }

    public void deleteImportDir(String storageName) {
        try {
            Path dir = importDir(storageName);
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                FileTrees.deleteRecursively(dir);
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not delete import directory {}: {}", storageName, e.getMessage());
        }
    }

    public void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                FileTrees.deleteRecursively(file);
            } else {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", file.getFileName(), e.getMessage());
        }
    }

    /** Removes temporary uploads and import directories that no database row references. */
    public int removeOrphans(Collection<String> referencedStorageNames) {
        Set<String> referenced = new HashSet<>(referencedStorageNames);
        int removed = 0;
        try (Stream<Path> entries = Files.list(importsDir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                String name = entry.getFileName().toString();
                if (STORAGE_NAME.matcher(name).matches() && !referenced.contains(name)) {
                    deleteQuietly(entry);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("Could not scan import storage: {}", e.getMessage());
        }
        try (Stream<Path> entries = Files.list(tmpDir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (entry.getFileName().toString().startsWith("upload-")) {
                    deleteQuietly(entry);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("Could not scan temporary storage: {}", e.getMessage());
        }
        return removed;
    }
}
