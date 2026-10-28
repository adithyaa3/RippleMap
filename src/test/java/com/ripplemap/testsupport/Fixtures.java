package com.ripplemap.testsupport;

import com.ripplemap.analysis.AnalysisRequest;
import com.ripplemap.analysis.JavaProjectAnalyzer;
import com.ripplemap.analysis.model.AggregatedRelationship;
import com.ripplemap.analysis.model.AnalysisResult;
import com.ripplemap.analysis.model.EntityKind;
import com.ripplemap.analysis.model.ExtractedEntity;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.RelationshipKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Test helpers for the golden fixture projects in src/test/resources/fixtures. */
public final class Fixtures {

    private Fixtures() {
    }

    public static Path path(String name) {
        URL url = Fixtures.class.getClassLoader().getResource("fixtures/" + name);
        if (url == null) {
            throw new IllegalArgumentException("Missing fixture " + name);
        }
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static AnalysisResult analyze(String name) {
        return analyze(path(name), false);
    }

    public static AnalysisResult analyze(Path root, boolean includeTests) {
        try {
            return new JavaProjectAnalyzer().analyze(AnalysisRequest.defaults(root, includeTests));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Unique type-level dependency pairs as "source->target". */
    public static Set<String> typePairs(AnalysisResult result) {
        return pairs(result, GraphLayer.TYPE);
    }

    public static Set<String> pairs(AnalysisResult result, GraphLayer layer) {
        Set<String> pairs = new TreeSet<>();
        for (AggregatedRelationship r : result.relationships()) {
            if (r.layer() == layer) {
                pairs.add(r.sourceId() + "->" + r.targetId());
            }
        }
        return pairs;
    }

    /** Relationships as "source->target:KIND". */
    public static Set<String> relationships(AnalysisResult result, GraphLayer layer) {
        Set<String> out = new TreeSet<>();
        for (AggregatedRelationship r : result.relationships()) {
            if (r.layer() == layer) {
                out.add(r.sourceId() + "->" + r.targetId() + ":" + r.kind());
            }
        }
        return out;
    }

    public static AggregatedRelationship relationship(AnalysisResult result, GraphLayer layer, String source,
                                                      String target, RelationshipKind kind) {
        return result.relationships().stream()
                .filter(r -> r.layer() == layer && r.sourceId().equals(source) && r.targetId().equals(target)
                        && r.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing relationship " + source + "->" + target + ":" + kind));
    }

    public static Set<String> entityIds(AnalysisResult result, EntityKind... kinds) {
        Set<EntityKind> wanted = Set.of(kinds);
        Set<String> ids = new TreeSet<>();
        for (ExtractedEntity e : result.entities()) {
            if (wanted.contains(e.kind())) {
                ids.add(e.stableId());
            }
        }
        return ids;
    }

    public static Set<String> typeIds(AnalysisResult result) {
        return entityIds(result, EntityKind.CLASS, EntityKind.INTERFACE, EntityKind.ENUM, EntityKind.RECORD,
                EntityKind.ANNOTATION);
    }

    public static ExtractedEntity entity(AnalysisResult result, String id) {
        return result.entities().stream().filter(e -> e.stableId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing entity " + id));
    }

    /** Copies a fixture to a fresh directory (for tests that modify files). */
    public static Path copy(String name, Path target) {
        Path source = path(name);
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                Path dest = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }

    public static void write(Path root, String relative, String content) {
        try {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
