package com.ripplemap.importing;

import com.ripplemap.importing.ArchiveRejectedException.Reason;
import com.ripplemap.testsupport.RawZipBuilder;
import com.ripplemap.testsupport.ZipFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeZipExtractorTest {

    private static final SafeZipExtractor.Limits LIMITS = new SafeZipExtractor.Limits(
            10L * 1024 * 1024, 2L * 1024 * 1024, 1000, 500, 40, 512, 200);

    @TempDir
    Path temp;

    private Path archive(byte[] bytes) throws IOException {
        Path file = temp.resolve("upload-" + System.nanoTime() + ".zip");
        Files.write(file, bytes);
        return file;
    }

    private Path dest() throws IOException {
        return Files.createDirectory(temp.resolve("dest-" + System.nanoTime()));
    }

    private SafeZipExtractor.Result extract(byte[] zip) throws Exception {
        return SafeZipExtractor.extract(archive(zip), dest(), LIMITS);
    }

    private void assertRejected(byte[] zip, Reason reason) {
        assertRejected(zip, reason, LIMITS);
    }

    private void assertRejected(byte[] zip, Reason reason, SafeZipExtractor.Limits limits) {
        assertThatThrownBy(() -> SafeZipExtractor.extract(archive(zip), dest(), limits))
                .isInstanceOfSatisfying(ArchiveRejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static List<Path> filesBelow(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void validArchiveExtractsOnlyJavaFiles() throws Exception {
        Path destination = dest();
        byte[] zip = new RawZipBuilder()
                .addDirectory("proj/src/main/java/a")
                .add("proj/src/main/java/a/A.java", "package a; class A {}")
                .add("proj/pom.xml", "<project/>")
                .add("proj/README.md", "# readme")
                .add("proj/src/main/java/a/B.JAVA", "package a; class B {}")
                .build();
        SafeZipExtractor.Result result = SafeZipExtractor.extract(archive(zip), destination, LIMITS);
        assertThat(result.javaFileCount()).isEqualTo(2);
        assertThat(result.skippedEntries()).isEqualTo(2);
        assertThat(result.entryCount()).isEqualTo(5);
        assertThat(result.javaFiles()).containsExactly("proj/src/main/java/a/A.java", "proj/src/main/java/a/B.JAVA");
        assertThat(Files.readString(destination.resolve("proj/src/main/java/a/A.java"))).isEqualTo("package a; class A {}");
        assertThat(Files.exists(destination.resolve("proj/pom.xml"))).isFalse();
    }

    @Test
    void fixtureArchiveRoundTrips() throws Exception {
        SafeZipExtractor.Result result = extract(ZipFixtures.zip("chain"));
        assertThat(result.javaFileCount()).isEqualTo(3);
    }

    @Test
    void emptyArchiveIsRejected() {
        assertRejected(new RawZipBuilder().build(), Reason.EMPTY_ARCHIVE);
    }

    @Test
    void nonZipContentIsRejected() {
        assertRejected("this is not a zip".getBytes(StandardCharsets.UTF_8), Reason.INVALID_ARCHIVE);
        byte[] truncated = Arrays.copyOf(ZipFixtures.zip("chain"), 200);
        assertRejected(truncated, Reason.INVALID_ARCHIVE);
    }

    @Test
    void archiveWithoutJavaIsRejected() {
        assertRejected(new RawZipBuilder().add("readme.txt", "hi").add("a/b.xml", "<x/>").build(), Reason.NO_JAVA_FILES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/etc/Evil.java", "\\\\server\\share\\Evil.java", "C:/Windows/Evil.java", "c:Evil.java"})
    void absolutePathsAreRejected(String name) {
        assertRejected(new RawZipBuilder().add(name, "class Evil {}").build(), Reason.ABSOLUTE_PATH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../Evil.java", "a/../../Evil.java", "a/b/../../../Evil.java", "..\\Evil.java", "a\\..\\..\\Evil.java"})
    void parentTraversalIsRejected(String name) throws IOException {
        Path destination = dest();
        assertThatThrownBy(() -> SafeZipExtractor.extract(archive(new RawZipBuilder().add("ok/A.java", "class A {}")
                .add(name, "class Evil {}").build()), destination, LIMITS))
                .isInstanceOfSatisfying(ArchiveRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.PATH_TRAVERSAL));
        assertThat(Files.exists(temp.resolve("Evil.java"))).isFalse();
        assertThat(Files.exists(destination.getParent().resolve("Evil.java"))).isFalse();
    }

    @Test
    void innerParentSegmentsAreRejectedEvenWhenTheyStayInside() {
        // a/b/../c.java would stay inside, but '..' is never legitimate in exported archives
        assertRejected(new RawZipBuilder().add("a/b/../C.java", "class C {}").build(), Reason.PATH_TRAVERSAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a/CON.java", "a/nul", "a/Evil.java:stream", "a/name./X.java", "a/trailing /X.java", "a/\u0001ctl.java"})
    void reservedOrAmbiguousNamesAreRejected(String name) {
        assertThatThrownBy(() -> extract(new RawZipBuilder().add(name, "class X {}").build()))
                .isInstanceOfSatisfying(ArchiveRejectedException.class,
                        e -> assertThat(e.reason()).isIn(Reason.INVALID_PATH, Reason.ABSOLUTE_PATH));
    }

    @Test
    void duplicateEntriesAreRejected() {
        assertRejected(new RawZipBuilder().add("a/A.java", "class A {}").add("a/A.java", "class B {}").build(),
                Reason.DUPLICATE_ENTRY);
        assertRejected(new RawZipBuilder().add("a/A.java", "class A {}").add("A/a.JAVA", "class B {}").build(),
                Reason.DUPLICATE_ENTRY);
        assertRejected(new RawZipBuilder().add("a/A.java", "class A {}").add("./a//A.java", "class B {}").build(),
                Reason.DUPLICATE_ENTRY);
    }

    @Test
    void symbolicLinkEntriesNeverBecomeLinks() throws Exception {
        Path destination = dest();
        byte[] zip = new RawZipBuilder()
                .addSymlink("p/Link.java", "../../../../../etc/passwd")
                .add("p/A.java", "class A {}")
                .build();
        SafeZipExtractor.extract(archive(zip), destination, LIMITS);
        Path link = destination.resolve("p/Link.java");
        assertThat(Files.isSymbolicLink(link)).isFalse();
        assertThat(Files.isRegularFile(link, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.readString(link)).isEqualTo("../../../../../etc/passwd");
        assertThat(filesBelow(destination)).allMatch(p -> p.startsWith(destination));
    }

    @Test
    void oversizedEntryIsRejectedByDeclaredSize() {
        byte[] big = new byte[3 * 1024 * 1024];
        assertRejected(new RawZipBuilder().add("A.java", big).build(), Reason.ENTRY_TOO_LARGE);
    }

    @Test
    void oversizedEntryIsRejectedWhileStreamingWhenHeadersLie() throws IOException {
        byte[] big = new byte[3 * 1024 * 1024];
        Arrays.fill(big, (byte) ' ');
        Path destination = dest();
        assertThatThrownBy(() -> SafeZipExtractor.extract(
                archive(new RawZipBuilder().addDeflatedWithDeclaredSize("Bomb.java", big, 100).build()), destination, LIMITS))
                .isInstanceOfSatisfying(ArchiveRejectedException.class,
                        e -> assertThat(e.reason()).isIn(Reason.ENTRY_TOO_LARGE, Reason.INVALID_ARCHIVE));
        for (Path p : filesBelow(destination)) {
            assertThat(Files.size(p)).isLessThanOrEqualTo(LIMITS.maxEntryBytes() + 16 * 1024);
        }
    }

    @Test
    void highCompressionRatioIsRejectedAsZipBomb() {
        byte[] repetitive = new byte[1_500_000];
        Arrays.fill(repetitive, (byte) 'a');
        assertRejected(new RawZipBuilder().addDeflated("Bomb.java", repetitive).build(), Reason.SUSPICIOUS_COMPRESSION);
    }

    @Test
    void totalExpandedSizeIsLimited() {
        SafeZipExtractor.Limits small = new SafeZipExtractor.Limits(1000, 800, 100, 100, 40, 512, 200);
        byte[] part = new byte[600];
        Arrays.fill(part, (byte) 'x');
        assertRejected(new RawZipBuilder().add("A.java", part).add("B.java", part).build(), Reason.EXPANDED_TOO_LARGE, small);
    }

    @Test
    void entryAndJavaFileCountsAreLimited() {
        SafeZipExtractor.Limits small = new SafeZipExtractor.Limits(1_000_000, 10_000, 3, 2, 40, 512, 200);
        assertRejected(new RawZipBuilder().add("A.java", "a").add("B.java", "b").add("c.txt", "c").add("d.txt", "d").build(),
                Reason.TOO_MANY_ENTRIES, small);
        assertRejected(new RawZipBuilder().add("A.java", "a").add("B.java", "b").add("C.java", "c").build(),
                Reason.TOO_MANY_JAVA_FILES, small);
    }

    @Test
    void pathDepthAndLengthAreLimited() {
        String deep = "a/".repeat(41) + "A.java";
        assertRejected(new RawZipBuilder().add(deep, "class A {}").build(), Reason.PATH_TOO_DEEP);
        String longName = "x".repeat(600) + ".java";
        assertRejected(new RawZipBuilder().add(longName, "class A {}").build(), Reason.PATH_TOO_LONG);
    }

    @Test
    void fileDirectoryConflictIsRejected() {
        assertRejected(new RawZipBuilder().add("a/X.java", "class X {}").add("a/X.java/Y.java", "class Y {}").build(),
                Reason.INVALID_PATH);
    }

    @Test
    void validateNameNormalizesHarmlessForms() throws Exception {
        assertThat(SafeZipExtractor.validateName("./a//b/./C.java", LIMITS)).isEqualTo("a/b/C.java");
        assertThat(SafeZipExtractor.validateName("a\\b\\C.java", LIMITS)).isEqualTo("a/b/C.java");
        assertThat(SafeZipExtractor.validateName("./", LIMITS)).isEmpty();
    }
}
