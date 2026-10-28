package com.ripplemap.analysis.parser;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds Java source files below a root directory without following symbolic links,
 * and classifies them as production, test or generated build output.
 *
 * <p>Classification rules (path based, see docs/ANALYSIS_SEMANTICS.md):
 * <ul>
 *   <li>Build output: a {@code target}, {@code build} or {@code out} directory directly containing
 *       {@code generated-sources}, {@code generated-test-sources}, {@code generated}, {@code classes},
 *       {@code test-classes}, {@code tmp} or {@code production}.</li>
 *   <li>Test: a {@code src} directory directly containing {@code test}, {@code tests}, {@code it}, or a
 *       test source set such as {@code testFixtures}, {@code integrationTest}, {@code functional-test};
 *       or a top-level {@code test}/{@code tests} directory (after skipping a single wrapper directory).</li>
 *   <li>Everything else is production source.</li>
 * </ul>
 * Hidden directories (starting with '.') and {@code node_modules} are not visited.
 */
public final class SourceDiscovery {

    private static final Set<String> BUILD_DIRS = Set.of("target", "build", "out");
    private static final Set<String> BUILD_CHILDREN = Set.of(
            "generated-sources", "generated-test-sources", "generated", "classes", "test-classes", "tmp", "production");
    /** test, tests, it, testFixtures, test-utils, integrationTest, functional-test, androidTest ... (not "latest"). */
    private static final Pattern TEST_SOURCE_SET =
            Pattern.compile("test|tests|it|test[A-Z_\\-].*|.*[a-z0-9]Test|.*[_\\-]test");
    private static final Set<String> TOP_LEVEL_TEST_DIRS = Set.of("test", "tests");

    public record Result(List<DiscoveredFile> files, List<String> skippedLinks) {
    }

    private SourceDiscovery() {
    }

    public static Result discover(Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Source root is not a directory");
        }
        List<String> javaPaths = new ArrayList<>();
        List<String> skippedLinks = new ArrayList<>();
        Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(normalizedRoot)) {
                    return FileVisitResult.CONTINUE;
                }
                // Windows junctions are reported as directories that are also "other" (reparse points);
                // like symbolic links they could lead outside the analyzed root, so they are never entered
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    skippedLinks.add(relative(normalizedRoot, dir));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                String name = dir.getFileName().toString();
                if (name.startsWith(".") || name.equals("node_modules")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = relative(normalizedRoot, file);
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    skippedLinks.add(rel);
                } else if (attrs.isRegularFile() && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java")) {
                    javaPaths.add(rel);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                skippedLinks.add(relative(normalizedRoot, file));
                return FileVisitResult.CONTINUE;
            }
        });
        javaPaths.sort(Comparator.naturalOrder());
        skippedLinks.sort(Comparator.naturalOrder());
        String wrapper = commonWrapperDirectory(javaPaths);
        List<DiscoveredFile> files = new ArrayList<>(javaPaths.size());
        for (String rel : javaPaths) {
            files.add(new DiscoveredFile(rel, normalizedRoot.resolve(rel), classify(rel, wrapper)));
        }
        return new Result(List.copyOf(files), List.copyOf(skippedLinks));
    }

    /** Classifies a relative path (with '/' separators). {@code wrapper} may be null. */
    public static SourceScope classify(String relativePath, String wrapper) {
        String[] segments = relativePath.split("/");
        int dirCount = segments.length - 1; // the last segment is the file name
        for (int i = 0; i + 1 < dirCount; i++) {
            if (BUILD_DIRS.contains(segments[i]) && BUILD_CHILDREN.contains(segments[i + 1])) {
                return SourceScope.BUILD_OUTPUT;
            }
        }
        for (int i = 0; i + 1 < dirCount; i++) {
            if (segments[i].equals("src") && TEST_SOURCE_SET.matcher(segments[i + 1]).matches()) {
                return SourceScope.TEST;
            }
        }
        int first = (wrapper != null && dirCount >= 1 && segments[0].equals(wrapper)) ? 1 : 0;
        if (first < dirCount && TOP_LEVEL_TEST_DIRS.contains(segments[first])) {
            return SourceScope.TEST;
        }
        return SourceScope.MAIN;
    }

    /** Returns the single top-level directory shared by all files, or null. */
    static String commonWrapperDirectory(List<String> paths) {
        String common = null;
        for (String path : paths) {
            int slash = path.indexOf('/');
            if (slash < 0) {
                return null;
            }
            String first = path.substring(0, slash);
            if (common == null) {
                common = first;
            } else if (!common.equals(first)) {
                return null;
            }
        }
        if (common != null && (common.equals("src") || TOP_LEVEL_TEST_DIRS.contains(common))) {
            return null;
        }
        return common;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
