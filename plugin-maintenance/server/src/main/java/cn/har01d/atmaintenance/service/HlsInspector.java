package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.HlsPlaylist;
import cn.har01d.atmaintenance.dto.HlsKey;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded basic HLS inventory. Unsupported resource semantics remain blocked. */
final class HlsInspector {
    private static final Pattern BANDWIDTH = Pattern.compile("(?:^|,)BANDWIDTH=([1-9][0-9]*)(?:,|$)");
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(\"[^\"\\r\\n]*\"|[^,\"\\s]+)(?:,|$)");
    private static final BigInteger MAX_SEQUENCE = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    static HlsPlaylist parse(String text) {
        String[] lines = text.replace("\r", "").split("\n");
        if (lines.length == 0 || lines.length > 4096 || !lines[0].equals("#EXTM3U")) invalid();
        List<String> variants = new ArrayList<>(), segments = new ArrayList<>();
        List<Double> durations = new ArrayList<>();
        List<HlsKey> keys = new ArrayList<>();
        HlsKey key = new HlsKey(0, "NONE", null, null, true);
        boolean variantPending = false, encrypted = false, map = false, range = false, end = false;
        boolean versionSeen = false, sequenceSeen = false, keySeen = false, unsupported = false, iFrames = false;
        int version = 1, requiredKeyVersion = 1, declarations = 0;
        BigInteger sequence = BigInteger.ZERO;
        long targetDuration = 0;
        Double duration = null;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                if (targetDuration != 0 || !line.substring(line.indexOf(':') + 1).matches("[1-9][0-9]{0,8}")) invalid();
                targetDuration = Long.parseLong(line.substring(line.indexOf(':') + 1));
            } else if (line.startsWith("#EXT-X-VERSION:")) {
                String value = line.substring(line.indexOf(':') + 1);
                if (versionSeen || !value.matches("[1-9][0-9]{0,8}")) invalid();
                version = Integer.parseInt(value);
                versionSeen = true;
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                String value = line.substring(line.indexOf(':') + 1);
                if (sequenceSeen || !segments.isEmpty() || !value.matches("[0-9]{1,20}")) invalid();
                sequence = new BigInteger(value);
                if (sequence.compareTo(MAX_SEQUENCE) > 0) invalid();
                sequenceSeen = true;
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                if (variantPending || duration != null || !BANDWIDTH.matcher(line.substring(18)).find()) invalid();
                variantPending = true;
            } else if (line.startsWith("#EXTINF:")) {
                if (duration != null || variantPending || end || !line.substring(8).matches("[0-9]+(?:\\.[0-9]+)?,.*")) invalid();
                try {
                    duration = Double.valueOf(line.substring(8).split(",", 2)[0]);
                    if (!Double.isFinite(duration) || duration <= 0) invalid();
                } catch (NumberFormatException error) { invalid(); }
            } else if (line.startsWith("#EXT-X-KEY:")) {
                Map<String, String> attributes = attributes(line.substring(11));
                String method = attributes.get("METHOD");
                if (method == null || !method.matches("[A-Z0-9-]+")) invalid();
                keySeen = true;
                if (method.equals("NONE")) {
                    if (attributes.size() != 1) invalid();
                    key = new HlsKey(++declarations, method, null, null, true);
                } else {
                    String uri = quoted(attributes.get("URI"));
                    String iv = attributes.get("IV");
                    if (uri.isEmpty() || iv != null && !iv.matches("0[xX][0-9A-Fa-f]{1,32}")) invalid();
                    if (iv != null) requiredKeyVersion = Math.max(requiredKeyVersion, 2);
                    String format = attributes.containsKey("KEYFORMAT") ? quoted(attributes.get("KEYFORMAT")) : "identity";
                    String formats = attributes.containsKey("KEYFORMATVERSIONS")
                            ? quoted(attributes.get("KEYFORMATVERSIONS")) : "1";
                    if (format.isEmpty() || !validFormats(formats)) invalid();
                    if (attributes.containsKey("KEYFORMAT") || attributes.containsKey("KEYFORMATVERSIONS"))
                        requiredKeyVersion = 5;
                    boolean supported = method.equals("AES-128") && format.equals("identity") && formats.equals("1")
                            && Set.of("METHOD", "URI", "IV", "KEYFORMAT", "KEYFORMATVERSIONS").containsAll(attributes.keySet());
                    unsupported |= !supported;
                    key = new HlsKey(++declarations, method, uri, iv, supported);
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                map = true;
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                range = true;
            } else if (line.equals("#EXT-X-I-FRAMES-ONLY")) {
                iFrames = true;
            } else if (line.equals("#EXT-X-ENDLIST")) {
                end = true;
            } else if (!line.startsWith("#")) {
                if (variantPending) { variants.add(line); variantPending = false; }
                else if (duration != null) {
                    segments.add(line); durations.add(duration); keys.add(key);
                    encrypted |= key.encrypted();
                    duration = null;
                }
                else invalid();
            }
        }
        if (variantPending || duration != null || variants.isEmpty() && segments.isEmpty()
                || !variants.isEmpty() && !segments.isEmpty()) invalid();
        if (!segments.isEmpty() && (targetDuration == 0
                || durations.stream().mapToLong(Math::round).max().orElseThrow() > targetDuration)) invalid();
        if (version < requiredKeyVersion
                || sequence.add(BigInteger.valueOf(Math.max(0, segments.size() - 1))).compareTo(MAX_SEQUENCE) > 0) invalid();
        if (!variants.isEmpty() && (targetDuration > 0 || sequenceSeen || end || map || range || keySeen || iFrames)) invalid();
        return new HlsPlaylist(List.copyOf(variants), List.copyOf(segments), List.copyOf(durations),
                encrypted, map, range, end, List.copyOf(keys), sequence, unsupported, iFrames);
    }

    private static Map<String, String> attributes(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(text);
        int offset = 0;
        while (offset < text.length()) {
            // Do not search later offsets: a long malformed attribute must be rejected in one scan.
            if (!matcher.region(offset, text.length()).lookingAt()
                    || values.putIfAbsent(matcher.group(1), matcher.group(2)) != null) invalid();
            offset = matcher.end();
        }
        if (offset != text.length() || values.isEmpty() || text.endsWith(",")) invalid();
        return values;
    }

    private static String quoted(String value) {
        if (value == null || !value.startsWith("\"") || !value.endsWith("\"") || value.length() < 2) invalid();
        return value.substring(1, value.length() - 1);
    }

    private static boolean validFormats(String value) {
        // A repeated regex group can overflow Java's stack on a bounded but long version list.
        boolean start = true;
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            if (start) {
                if (item < '1' || item > '9') return false;
                start = false;
            } else if (item == '/') start = true;
            else if (item < '0' || item > '9') return false;
        }
        return !start;
    }

    private static void invalid() { throw new IllegalArgumentException("MEDIA_HLS_INVALID"); }
}
