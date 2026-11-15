package io.solarae.sol.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * A log file that cannot grow without bound: when it reaches {@code maxBytes}
 * it is renamed to {@code <name>.1} (replacing the previous one) and a new
 * file is started, so at most about twice {@code maxBytes} is ever on disk.
 *
 * The node's output is written here line by line, and the app adds its own
 * lines (starts, exits, network changes). Thread-safe.
 */
public final class RotatingLog {
    private final File file;
    private final File old;
    private final long maxBytes;
    private OutputStream out;
    private long size;

    /**
     * @param file     the current log; {@code <file>.1} holds the one before
     * @param maxBytes rotate when the current log reaches this size
     */
    public RotatingLog(File file, long maxBytes) {
        this.file = file;
        this.old = new File(file.getPath() + ".1");
        this.maxBytes = maxBytes;
    }

    /** Appends one line. Errors are swallowed: logging must never stop the node. */
    public synchronized void line(String s) {
        try {
            byte[] b = (s + "\n").getBytes(StandardCharsets.UTF_8);
            if (out == null) {
                size = file.length();
                out = new FileOutputStream(file, true);
            }
            if (size > 0 && size + b.length > maxBytes) {
                out.close();
                out = null;
                old.delete();
                if (!file.renameTo(old)) {
                    file.delete();
                }
                size = 0;
                out = new FileOutputStream(file, true);
            }
            out.write(b);
            out.flush();
            size += b.length;
        } catch (IOException e) {
            close();
        }
    }

    /** Closes the file; the next {@link #line} reopens it. */
    public synchronized void close() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
            out = null;
        }
    }

    /**
     * The end of the log: up to {@code maxBytes} of it, starting at a line
     * boundary, reaching back into the previous file when the current one
     * is short.
     */
    public String tail(int maxBytes) {
        return tail(file, maxBytes);
    }

    /** As {@link #tail(int)}, for any log that rotates to {@code <file>.1}. */
    public static String tail(File file, int maxBytes) {
        String cur = tailOf(file, maxBytes);
        if (cur.length() < maxBytes / 2) {
            String before = tailOf(new File(file.getPath() + ".1"), maxBytes - cur.length());
            return before + cur;
        }
        return cur;
    }

    private static String tailOf(File f, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            long from = Math.max(0, len - maxBytes);
            byte[] b = new byte[(int) (len - from)];
            r.seek(from);
            r.readFully(b);
            int start = 0;
            if (from > 0) {
                // Drop the partial first line.
                while (start < b.length && b[start] != '\n') {
                    start++;
                }
                start = Math.min(b.length, start + 1);
            }
            return new String(b, start, b.length - start, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** The last {@code n} lines of {@code text}. */
    public static String lastLines(String text, int n) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        int i = end;
        for (int lines = 0; i > 0; i--) {
            if (text.charAt(i - 1) == '\n' && ++lines == n) {
                break;
            }
        }
        return text.substring(i, end);
    }
}
