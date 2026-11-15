package io.solarae.sol.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for the node's own answers (GET /api/status and
 * /api/files on loopback). Objects become {@link Map}, arrays {@link List},
 * and the rest String, Double, Boolean or null.
 *
 * It exists so the status logic can be tested on a plain JVM, where
 * Android's org.json is not available. Nesting is limited, so a hostile
 * answer cannot overflow the stack.
 */
public final class Json {
    private static final int MAX_DEPTH = 32;
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /**
     * Parses one JSON value.
     *
     * @throws IllegalArgumentException when {@code text} is not JSON
     */
    public static Object parse(String text) {
        Json j = new Json(text);
        try {
            Object v = j.value(0);
            j.ws();
            if (j.i != text.length()) {
                throw new IllegalArgumentException("trailing data");
            }
            return v;
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            throw new IllegalArgumentException("bad JSON", e);
        }
    }

    /** {@code o[key]} when {@code o} is an object, else null. */
    public static Object get(Object o, String key) {
        return o instanceof Map ? ((Map<?, ?>) o).get(key) : null;
    }

    /** {@code o[key]} as a string, or "" when it is absent or not a string. */
    public static String str(Object o, String key) {
        Object v = get(o, key);
        return v instanceof String ? (String) v : "";
    }

    /** {@code o[key]} as a list, or an empty list. */
    public static List<?> list(Object o, String key) {
        Object v = get(o, key);
        return v instanceof List ? (List<?>) v : new ArrayList<>();
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("nested too deeply");
        }
        ws();
        char c = s.charAt(i);
        switch (c) {
            case '{': {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    m.put(k, value(depth + 1));
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect('}');
                    return m;
                }
            }
            case '[': {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value(depth + 1));
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect(']');
                    return l;
                }
            }
            case '"':
                return string();
            case 't':
                word("true");
                return Boolean.TRUE;
            case 'f':
                word("false");
                return Boolean.FALSE;
            case 'n':
                word("null");
                return null;
            default: {
                int start = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                    i++;
                }
                if (start == i) {
                    throw new IllegalArgumentException("unexpected character");
                }
                return Double.valueOf(s.substring(start, i));
            }
        }
    }

    private void expect(char c) {
        if (s.charAt(i) != c) {
            throw new IllegalArgumentException("expected " + c);
        }
        i++;
    }

    private void word(String w) {
        if (!s.startsWith(w, i)) {
            throw new IllegalArgumentException("unexpected word");
        }
        i += w.length();
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            c = s.charAt(i++);
            switch (c) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: b.append(c); // \" \\ \/
            }
        }
    }
}
