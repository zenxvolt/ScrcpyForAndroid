package org.client.scrcpy.utils;

import android.text.TextUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers to turn whatever the user typed or pasted into a clean
 * {@code host[:port]} target that adb understands.
 * <p>
 * Accepted examples (all normalise to {@code 192.168.1.5:5555}):
 * <pre>
 *   192.168.1.5
 *   192.168.1.5:5555
 *   " 192.168.1.5 : 5555 "
 *   adb connect 192.168.1.5:5555
 *   adb -s 192.168.1.5:5555 shell
 *   tcp://192.168.1.5:5555
 *   192,168,1,5            (comma keypad on some locales)
 * </pre>
 */
public final class AdbAddress {

    public static final int DEFAULT_PORT = 5555;

    private static final Pattern IPV4_WITH_PORT = Pattern.compile(
            "(?<![\\d.])((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})(?![\\d.])(?:\\s*:\\s*(\\d{1,5}))?");
    private static final Pattern HOSTNAME = Pattern.compile(
            "^[A-Za-z0-9]([A-Za-z0-9\\-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9\\-]{0,61}[A-Za-z0-9])?)*$");
    private static final Pattern IPV6_BRACKETED = Pattern.compile("^\\[([0-9A-Fa-f:.%a-zA-Z]+)](?::(\\d{1,5}))?$");

    private AdbAddress() {
    }

    /**
     * Normalise a user supplied address.
     *
     * @return {@code host:port} (port defaults to 5555), or {@code null} when the
     * text does not contain anything that looks like a device address.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        // Strip common prefixes people paste from tutorials / the terminal.
        String lower = text.toLowerCase(Locale.ROOT);
        for (String prefix : new String[]{"adb connect ", "adb -s ", "connect ", "adb "}) {
            if (lower.startsWith(prefix)) {
                text = text.substring(prefix.length()).trim();
                lower = text.toLowerCase(Locale.ROOT);
            }
        }
        for (String scheme : new String[]{"tcp://", "adb://", "http://", "https://"}) {
            if (lower.startsWith(scheme)) {
                text = text.substring(scheme.length());
                lower = text.toLowerCase(Locale.ROOT);
            }
        }
        // Some keyboards only offer ',' on the numeric pad.
        if (text.matches("[0-9,:\\s]+") && text.contains(",")) {
            text = text.replace(',', '.');
        }

        // IPv6 in brackets: [fe80::1]:5555
        Matcher v6 = IPV6_BRACKETED.matcher(text.split("\\s+")[0]);
        if (v6.matches()) {
            int port = parsePort(v6.group(2));
            return port < 0 ? null : "[" + v6.group(1) + "]:" + port;
        }

        // IPv4 anywhere in the text (also handles "IP address & Port 192.168.1.5:41234").
        Matcher v4 = IPV4_WITH_PORT.matcher(text);
        if (v4.find()) {
            int port = parsePort(v4.group(2));
            return port < 0 ? null : v4.group(1) + ":" + port;
        }

        // Host name, optionally with port (must be a single word).
        String[] tokens = text.split("\\s+");
        if (tokens.length != 1) {
            return null;
        }
        String token = tokens[0];
        String host = token;
        String portText = null;
        int colon = token.lastIndexOf(':');
        if (colon > 0 && token.indexOf(':') == colon) {
            host = token.substring(0, colon);
            portText = token.substring(colon + 1);
        }
        // Something like 999.1.1.1 is a broken IP, not a host name.
        if (host.matches("[\\d.]+")) {
            return null;
        }
        if (HOSTNAME.matcher(host).matches() && host.length() <= 253) {
            int port = parsePort(portText);
            return port < 0 ? null : host + ":" + port;
        }
        return null;
    }

    /** Port 1..65535, the default when empty, or -1 when invalid. */
    private static int parsePort(String text) {
        if (TextUtils.isEmpty(text)) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(text.trim());
            return (port > 0 && port <= 65535) ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Host part of a normalised {@code host:port} address. */
    public static String host(String address) {
        if (address == null) {
            return "";
        }
        int idx = address.lastIndexOf(':');
        if (idx <= 0 || address.endsWith("]")) {
            return address;
        }
        return address.substring(0, idx);
    }

    // ---------------------------------------------------------------------
    // Diagnosis of `adb connect` output
    // ---------------------------------------------------------------------

    public enum Failure {
        /** The device answered but is waiting for "Allow debugging?" */
        UNAUTHORIZED,
        /** Host reachable but nothing listens on that port. */
        REFUSED,
        /** Host not reachable (wrong IP / different network / AP isolation). */
        UNREACHABLE,
        /** Address could not be resolved. */
        BAD_ADDRESS,
        /** Device is listed but offline. */
        OFFLINE,
        /** Anything else, including timeouts. */
        TIMEOUT,
        UNKNOWN
    }

    /**
     * Classify the text printed by {@code adb connect host:port}.
     *
     * @return null when the output means "connected" / "already connected".
     */
    public static Failure classifyConnectOutput(String output) {
        if (output == null) {
            return Failure.UNKNOWN;
        }
        String o = output.toLowerCase(Locale.ROOT);
        if (o.contains("failed to authenticate") || o.contains("unauthorized")) {
            return Failure.UNAUTHORIZED;
        }
        if (o.contains("already connected") || o.contains("connected to")) {
            return null;
        }
        if (o.contains("connection refused")) {
            return Failure.REFUSED;
        }
        if (o.contains("no route to host") || o.contains("network is unreachable")
                || o.contains("host is unreachable")) {
            return Failure.UNREACHABLE;
        }
        if (o.contains("timed out") || o.contains("timeout")) {
            return Failure.TIMEOUT;
        }
        if (o.contains("failed to resolve") || o.contains("unknown host")
                || o.contains("name or service not known") || o.contains("no address associated")
                || o.contains("bad port number")) {
            return Failure.BAD_ADDRESS;
        }
        if (o.contains("offline")) {
            return Failure.OFFLINE;
        }
        return Failure.UNKNOWN;
    }
}
