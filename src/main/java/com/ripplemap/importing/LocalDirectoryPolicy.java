package com.ripplemap.importing;

import com.ripplemap.config.RippleMapProperties;
import com.ripplemap.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Authorizes local-directory projects. Only directories inside a configured allowed root may be scanned; the
 * check uses real paths (symbolic links resolved), so links cannot escape an allowed root. With no roots
 * configured, local scanning is disabled.
 */
@Component
public class LocalDirectoryPolicy {

    private static final Logger log = LoggerFactory.getLogger(LocalDirectoryPolicy.class);

    private final List<Path> allowedRoots = new ArrayList<>();

    public LocalDirectoryPolicy(RippleMapProperties properties) {
        for (String root : properties.getLocalScan().getAllowedRoots()) {
            if (root == null || root.isBlank()) {
                continue;
            }
            try {
                Path real = Paths.get(root.strip()).toRealPath();
                if (Files.isDirectory(real)) {
                    allowedRoots.add(real);
                } else {
                    log.warn("Ignoring local-scan root that is not a directory: {}", root);
                }
            } catch (IOException | InvalidPathException e) {
                log.warn("Ignoring local-scan root that does not exist: {}", root);
            }
        }
    }

    public boolean enabled() {
        return !allowedRoots.isEmpty();
    }

    public List<String> allowedRoots() {
        return allowedRoots.stream().map(Path::toString).toList();
    }

    /** Validates a user-supplied directory and returns its real path. */
    public Path authorize(String rawPath) {
        if (!enabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LOCAL_SCAN_DISABLED",
                    "Local directory scanning is disabled. Configure ripplemap.local-scan.allowed-roots to enable it.");
        }
        if (rawPath == null || rawPath.isBlank() || rawPath.length() > 1024 || rawPath.indexOf('\0') >= 0) {
            throw ApiException.validation("path", "Enter an absolute directory path");
        }
        Path path;
        try {
            path = Paths.get(rawPath.strip());
        } catch (InvalidPathException e) {
            throw ApiException.validation("path", "The path is not valid on this system");
        }
        if (!path.isAbsolute()) {
            throw ApiException.validation("path", "The path must be absolute");
        }
        Path real;
        try {
            real = path.toRealPath();
        } catch (NoSuchFileException e) {
            throw ApiException.validation("path", "The directory does not exist");
        } catch (IOException e) {
            throw ApiException.validation("path", "The directory cannot be accessed");
        }
        if (!Files.isDirectory(real)) {
            throw ApiException.validation("path", "The path is not a directory");
        }
        for (Path root : allowedRoots) {
            if (real.startsWith(root)) {
                return real;
            }
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "PATH_NOT_ALLOWED",
                "The directory is outside the allowed local-scan roots");
    }
}
