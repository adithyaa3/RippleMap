package com.ripplemap.analysis.parser;

import com.ripplemap.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Reads Java source files as UTF-8 text with a hard size limit. */
public final class JavaSourceReader {

    public record Content(String text, String sha256, long sizeBytes, int lineCount, boolean encodingReplaced) {
    }

    public static final class FileTooLargeException extends IOException {
        public FileTooLargeException(String message) {
            super(message);
        }
    }

    private JavaSourceReader() {
    }

    public static Content read(Path file, long maxBytes) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Not a regular file");
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
        }
        if (bytes.length > maxBytes) {
            throw new FileTooLargeException("File is larger than " + maxBytes + " bytes");
        }
        return decode(bytes);
    }

    public static Content decode(byte[] bytes) {
        String sha = Hashing.sha256Hex(bytes);
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
        String text;
        boolean replaced = false;
        try {
            CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer chars = strict.decode(buffer);
            text = chars.toString();
        } catch (CharacterCodingException e) {
            buffer = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
            CharsetDecoder lenient = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            try {
                text = lenient.decode(buffer).toString();
            } catch (CharacterCodingException impossible) {
                throw new IllegalStateException(impossible);
            }
            replaced = true;
        }
        return new Content(text, sha, bytes.length, countLines(text), replaced);
    }

    static int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines++;
            } else if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                lines++;
            }
        }
        char last = text.charAt(text.length() - 1);
        if (last == '\n' || last == '\r') {
            lines--;
        }
        return lines;
    }
}
