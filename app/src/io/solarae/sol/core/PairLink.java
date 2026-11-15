package io.solarae.sol.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Checks a {@code sol://pair?...} link that another app handed us, and turns
 * it into the panel address that opens the join screen with it filled in.
 *
 * The link is untrusted: any app or web page can fire one. Nothing is paired
 * here. The panel shows it and the user confirms. This class only refuses
 * what cannot be a pairing link, so junk never reaches the WebView.
 * The format is in the main repository's docs/PROTOCOL.md ("Pairing link").
 */
public final class PairLink {
    /** Real links are a few hundred characters. */
    public static final int MAX_LENGTH = 2048;
    private static final String PREFIX = "sol://pair?";

    private PairLink() {}

    /** Why {@code link} is not a pairing link, or null when it looks like one. */
    public static String problem(String link) {
        if (link == null || link.isEmpty()) {
            return "empty link";
        }
        if (link.length() > MAX_LENGTH) {
            return "link too long";
        }
        if (!link.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return "not a sol://pair link";
        }
        boolean node = false, secret = false;
        for (int i = 0; i < link.length(); i++) {
            char c = link.charAt(i);
            // A URL has no spaces, controls, quotes or non-ASCII characters.
            if (c <= 0x20 || c >= 0x7f || c == '"' || c == '\'' || c == '<' || c == '>' || c == '\\' || c == '`') {
                return "link has characters a URL cannot contain";
            }
        }
        if (link.indexOf('#') >= 0) {
            return "link has a fragment";
        }
        for (String kv : link.substring(PREFIX.length()).split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0 || eq == kv.length() - 1) {
                continue;
            }
            String k = kv.substring(0, eq);
            node |= k.equals("node");
            secret |= k.equals("s");
        }
        if (!node || !secret) {
            return "link is missing its node id or secret";
        }
        return null;
    }

    /**
     * The fragment (without '#') that makes the panel open its join screen
     * with {@code link} filled in: {@code pair=<the whole link, URL-encoded>}.
     * The panel reads it with decodeURIComponent.
     */
    public static String fragment(String link) {
        try {
            // URLEncoder writes a space as '+', which decodeURIComponent
            // leaves alone. A checked link has no spaces, but stay correct.
            return "pair=" + URLEncoder.encode(link, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e); // UTF-8 always exists
        }
    }
}
