package io.solarae.sol.core;

/**
 * Names for files saved to the phone. The name comes from a peer (they
 * named the file they sent), so it is cleaned before it is used.
 */
public final class FileNames {
    private static final int MAX = 120;

    private FileNames() {}

    /**
     * A name that is safe as a single file name: no directories, no control
     * characters, not hidden, not empty, not overly long. The extension is
     * kept when the name has to be shortened.
     */
    public static String safe(String name, String fallback) {
        StringBuilder b = new StringBuilder();
        if (name != null) {
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c < 0x20 || c == 0x7f || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                        || c == '"' || c == '<' || c == '>' || c == '|') {
                    c = '_';
                }
                b.append(c);
            }
        }
        String s = b.toString().trim();
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            s = fallback;
        }
        if (s.length() > MAX) {
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 && s.length() - dot <= 12 ? s.substring(dot) : "";
            s = s.substring(0, MAX - ext.length()) + ext;
        }
        return s;
    }

    /**
     * The file name in a Content-Disposition header, or null. Handles
     * {@code filename=x}, {@code filename="x y"} and RFC 5987
     * {@code filename*=UTF-8''x%20y}, which is what Go's mime package writes.
     */
    public static String fromContentDisposition(String header) {
        if (header == null) {
            return null;
        }
        String plain = null;
        for (String part : header.split(";")) {
            part = part.trim();
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String k = part.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
            String v = part.substring(eq + 1).trim();
            if (k.equals("filename*")) {
                int q = v.indexOf("''");
                if (q >= 0) {
                    try {
                        // URLDecoder turns '+' into a space; RFC 5987 does not.
                        return java.net.URLDecoder.decode(v.substring(q + 2).replace("+", "%2B"), "UTF-8");
                    } catch (Exception e) {
                        // fall through to the plain name
                    }
                }
            } else if (k.equals("filename")) {
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                }
                plain = v;
            }
        }
        return plain;
    }
}
