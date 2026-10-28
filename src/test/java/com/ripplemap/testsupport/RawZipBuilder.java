package com.ripplemap.testsupport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes ZIP archives byte by byte so tests can create archives that {@link java.util.zip.ZipOutputStream} refuses
 * to produce: duplicate entries, absolute or traversing names, Unix symlink attributes and lying size headers.
 */
public final class RawZipBuilder {

    private static final int UNIX_SYMLINK = 0120777 << 16;

    private record Entry(String name, byte[] data, boolean deflate, int externalAttributes, Long declaredSize) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public RawZipBuilder add(String name, String content) {
        return add(name, content.getBytes(StandardCharsets.UTF_8));
    }

    public RawZipBuilder add(String name, byte[] data) {
        entries.add(new Entry(name, data, false, 0, null));
        return this;
    }

    public RawZipBuilder addDeflated(String name, byte[] data) {
        entries.add(new Entry(name, data, true, 0, null));
        return this;
    }

    /** A deflated entry whose headers claim {@code declaredSize} uncompressed bytes. */
    public RawZipBuilder addDeflatedWithDeclaredSize(String name, byte[] data, long declaredSize) {
        entries.add(new Entry(name, data, true, 0, declaredSize));
        return this;
    }

    public RawZipBuilder addSymlink(String name, String target) {
        entries.add(new Entry(name, target.getBytes(StandardCharsets.UTF_8), false, UNIX_SYMLINK, null));
        return this;
    }

    public RawZipBuilder addDirectory(String name) {
        entries.add(new Entry(name.endsWith("/") ? name : name + "/", new byte[0], false, 0x10, null));
        return this;
    }

    public byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        for (Entry e : entries) {
            byte[] name = e.name().getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(e.data());
            byte[] stored = e.deflate() ? deflate(e.data()) : e.data();
            long size = e.declaredSize() != null ? e.declaredSize() : e.data().length;
            int offset = out.size();
            writeInt(out, 0x04034b50);
            writeShort(out, 20);
            writeShort(out, 0x0800);
            writeShort(out, e.deflate() ? 8 : 0);
            writeShort(out, 0);
            writeShort(out, 0x21);
            writeInt(out, (int) crc.getValue());
            writeInt(out, stored.length);
            writeInt(out, (int) size);
            writeShort(out, name.length);
            writeShort(out, 0);
            out.writeBytes(name);
            out.writeBytes(stored);

            writeInt(central, 0x02014b50);
            writeShort(central, 0x031E);
            writeShort(central, 20);
            writeShort(central, 0x0800);
            writeShort(central, e.deflate() ? 8 : 0);
            writeShort(central, 0);
            writeShort(central, 0x21);
            writeInt(central, (int) crc.getValue());
            writeInt(central, stored.length);
            writeInt(central, (int) size);
            writeShort(central, name.length);
            writeShort(central, 0);
            writeShort(central, 0);
            writeShort(central, 0);
            writeShort(central, 0);
            writeInt(central, e.externalAttributes());
            writeInt(central, offset);
            central.writeBytes(name);
        }
        int centralOffset = out.size();
        byte[] cd = central.toByteArray();
        out.writeBytes(cd);
        writeInt(out, 0x06054b50);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, entries.size());
        writeShort(out, entries.size());
        writeInt(out, cd.length);
        writeInt(out, centralOffset);
        writeShort(out, 0);
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buffer);
            out.write(buffer, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        writeShort(out, value & 0xFFFF);
        writeShort(out, (value >>> 16) & 0xFFFF);
    }
}
