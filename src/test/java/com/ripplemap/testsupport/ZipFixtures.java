package com.ripplemap.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds ZIP archives of fixture directories (with a wrapper directory, as exported by most tools). */
public final class ZipFixtures {

    private ZipFixtures() {
    }

    public static byte[] zip(String fixture) {
        return zip(Fixtures.path(fixture), fixture.replace('/', '-'));
    }

    public static byte[] zip(Path root, String wrapper) {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (Path file : files) {
                    String name = wrapper + "/" + root.relativize(file).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(Files.readAllBytes(file));
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
