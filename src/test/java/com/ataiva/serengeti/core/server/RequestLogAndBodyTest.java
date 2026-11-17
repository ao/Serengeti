package com.ataiva.serengeti.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The request log's duration and readBody against the code they replaced (from review 22g). */
class RequestLogAndBodyTest {
    private static String percentF(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1e6);
    }

    private static String millis(long nanos) {
        StringBuilder b = new StringBuilder();
        RequestLog.millis(b, nanos);
        return b.toString();
    }

    @Test
    void theDurationIsWhatPercentFWroteForEveryHalfUpToTenSeconds() {
        int bad = 0;
        List<Long> first = new ArrayList<>();
        for (long n = 0; n <= 2_000_000; n++) {
            if (!percentF(n).equals(millis(n))) {
                bad++;
                if (first.size() < 10) {
                    first.add(n);
                }
            }
        }
        for (long k = 0; k < 10_000_000; k++) {
            long n = k * 1000 + 500;
            if (!percentF(n).equals(millis(n))) {
                bad++;
                if (first.size() < 10) {
                    first.add(n);
                }
            }
        }
        Random r = new Random(22);
        for (int i = 0; i < 5_000_000; i++) {
            long scale = (long) Math.pow(10, 7 + r.nextInt(9));
            long n = Math.floorMod(r.nextLong(), scale) / 1000 * 1000 + (r.nextBoolean() ? 500 : r.nextInt(1000));
            if (n < 1_000_000_000_000_000L && !percentF(n).equals(millis(n))) {
                bad++;
                if (first.size() < 10) {
                    first.add(n);
                }
            }
        }
        List<String> shown = new ArrayList<>();
        for (long n : first) {
            shown.add(n + ": %.3f " + percentF(n) + " vs " + millis(n));
        }
        System.out.println("R22G java " + System.getProperty("java.version") + " millis mismatches " + bad + " " + shown);
        assertEquals(0, bad, shown.toString());
    }

    /** A request with these headers and this body, read in pieces of at most {@code piece} bytes. */
    private static final class Fake extends HttpExchange {
        final Headers headers = new Headers();
        final byte[] body;
        final int piece;
        final int failAt;
        int pos;

        Fake(String contentLength, byte[] body, int piece, int failAt) {
            if (contentLength != null) {
                headers.add("Content-Length", contentLength);
            }
            this.body = body;
            this.piece = piece;
            this.failAt = failAt;
        }

        final InputStream in = new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (failAt >= 0 && pos >= failAt) {
                    throw new IOException("broken");
                }
                if (pos >= body.length) {
                    return -1;
                }
                int n = Math.min(Math.min(len, piece), body.length - pos);
                if (failAt >= 0) {
                    n = Math.min(n, Math.max(1, failAt - pos));
                }
                System.arraycopy(body, pos, b, off, n);
                pos += n;
                return n;
            }
        };

        @Override public Headers getRequestHeaders() { return headers; }
        @Override public Headers getResponseHeaders() { return new Headers(); }
        @Override public URI getRequestURI() { return URI.create("/v1/query"); }
        @Override public String getRequestMethod() { return "POST"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return in; }
        @Override public OutputStream getResponseBody() { return OutputStream.nullOutputStream(); }
        @Override public void sendResponseHeaders(int rCode, long responseLength) {}
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(0); }
        @Override public int getResponseCode() { return -1; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress(0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public HttpPrincipal getPrincipal() { return null; }
    }

    /** readBody as it was before e57dd58, with tooLarge and drain as they are. */
    static byte[] oldReadBody(HttpExchange ex, int max) throws IOException {
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        if (len != null) {
            long declared;
            try {
                declared = Long.parseLong(len.trim());
            } catch (NumberFormatException e) {
                throw new HttpApi.HttpError(400, "bad Content-Length");
            }
            if (declared > max) {
                throw oldTooLarge(ex.getRequestBody(), max);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        InputStream in = ex.getRequestBody();
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > max) {
                    throw oldTooLarge(in, max);
                }
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            throw new HttpApi.HttpError(400, "the request body could not be read");
        }
        return out.toByteArray();
    }

    private static HttpApi.HttpError oldTooLarge(InputStream in, int max) {
        byte[] buf = new byte[65536];
        long left = HttpApi.DRAIN_LIMIT;
        try {
            int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                left -= n;
            }
        } catch (IOException ignored) {
            // as drain
        }
        return new HttpApi.HttpError(413, "request body is larger than " + max + " bytes");
    }

    private interface Reader {
        byte[] read(HttpExchange ex, int max) throws IOException;
    }

    private static String outcome(Reader reader, Fake ex, int max) {
        try {
            return "body " + Arrays.hashCode(reader.read(ex, max)) + " read " + ex.pos;
        } catch (HttpApi.HttpError e) {
            return e.status + " " + e.getMessage() + " read " + ex.pos;
        } catch (IOException | RuntimeException e) {
            return e.toString() + " read " + ex.pos;
        }
    }

    @Test
    void readBodyAnswersAsBeforeForEveryContentLength() {
        Random r = new Random(22);
        int max = HttpApi.MAX_BODY;
        int[] maxes = {max, 100, 255, 256, 257, 8191, 8192, 8193, 0, 1};
        int[] lengths = {0, 1, 99, 100, 101, 255, 256, 257, 300, 8191, 8192, 8193, 20_000, max - 1, max, max + 1, 3 * max};
        int cases = 0;
        for (int i = 0; i < 6_000; i++) {
            int m = maxes[r.nextInt(maxes.length)];
            int len = r.nextInt(4) == 0 ? r.nextInt(20_000) : lengths[r.nextInt(lengths.length)];
            byte[] body = new byte[len];
            r.nextBytes(body);
            long near = r.nextInt(3) == 0 ? len : r.nextInt(2) == 0 ? r.nextInt(9000) : (long) m + r.nextInt(3) - 1;
            String[] headers = {null, "0", "-1", "-99999999999", "+" + near, " " + near + " ", "abc", "", Long.toString(near),
                Long.toString(near - 1), Long.toString(near + 1), "9223372036854775807", "9223372036854775808", "1e3", "0x10"};
            String h = headers[r.nextInt(headers.length)];
            int piece = r.nextInt(3) == 0 ? 1 + r.nextInt(300) : 1 << r.nextInt(17);
            int failAt = r.nextInt(5) == 0 ? r.nextInt(len + 1) : -1;
            String want = outcome(RequestLogAndBodyTest::oldReadBody, new Fake(h, body, piece, failAt), m);
            String got = outcome(HttpApi::readBody, new Fake(h, body, piece, failAt), m);
            assertEquals(want, got, "Content-Length " + h + ", body " + len + ", max " + m + ", pieces " + piece
                    + ", fails at " + failAt);
            cases++;
        }
        System.out.println("R22G readBody cases " + cases);
    }
}
