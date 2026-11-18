package com.ataiva.serengeti;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Characters of UTF-8 input, one at a time, with one of look-ahead. Bytes that are not UTF-8 fail the read
 * (a {@link java.nio.charset.CharacterCodingException}) instead of becoming U+FFFD, and only once every character
 * before them has been read, so the line that holds them is known. A byte order mark at the start is skipped.
 */
final class Chars {
    private final InputStream in;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    private final ByteBuffer bytes = ByteBuffer.allocate(1 << 16).flip();
    private final CharBuffer chars = CharBuffer.allocate(1 << 16).flip();
    private boolean eof;
    private boolean flushed;
    private CoderResult failure;

    Chars(InputStream in) throws IOException {
        this.in = in;
        if (peek() == 0xFEFF) {
            read();
        }
    }

    /** The next character without taking it, or -1 at the end. */
    int peek() throws IOException {
        if (!chars.hasRemaining() && !fill()) {
            return -1;
        }
        return chars.get(chars.position());
    }

    /** The next character, or -1 at the end. */
    int read() throws IOException {
        if (!chars.hasRemaining() && !fill()) {
            return -1;
        }
        return chars.get();
    }

    private boolean fill() throws IOException {
        if (flushed) {
            return false;
        }
        chars.clear();
        for (;;) {
            if (failure != null) {
                if (chars.position() > 0) {
                    break;
                }
                failure.throwException();
            }
            CoderResult r = decoder.decode(bytes, chars, eof);
            if (r.isError()) {
                failure = r;
                continue;
            }
            if (r.isOverflow() || (chars.position() > 0 && !eof)) {
                break;
            }
            if (eof) {
                if (!flushed) {
                    decoder.flush(chars);
                    flushed = true;
                }
                break;
            }
            bytes.compact();
            int n = in.read(bytes.array(), bytes.position(), bytes.remaining());
            if (n < 0) {
                eof = true;
            } else {
                bytes.position(bytes.position() + n);
            }
            bytes.flip();
        }
        chars.flip();
        return chars.hasRemaining();
    }
}
