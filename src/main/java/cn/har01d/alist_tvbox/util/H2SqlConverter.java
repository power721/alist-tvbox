package cn.har01d.alist_tvbox.util;

import org.springframework.core.env.Environment;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts H2-dialect douban SQL (exported by the xiaoya-douban project via
 * {@code org.h2.tools.Script -options simple}) into dialect-portable statements
 * that MySQL / PostgreSQL can execute directly.
 *
 * <p>The H2 export uses several H2-only constructs that break on MySQL/PG:
 * <ul>
 *   <li>{@code "PUBLIC"."MOVIE"} schema-qualified, case-folded identifiers
 *       (H2 folds unquoted ids to UPPER, so the Flyway-built {@code movie} table
 *       matches {@code "PUBLIC"."MOVIE"} only on H2).</li>
 *   <li>{@code U&'\xxxx'} unicode string escapes (unsupported by MySQL).</li>
 *   <li>{@code TIMESTAMP WITH TIME ZONE '...'} typed literals.</li>
 *   <li>{@code CREATE CACHED TABLE ... SELECTIVITY} DDL (target tables already
 *       exist via Flyway, so DDL lines are skipped entirely).</li>
 * </ul>
 *
 * <p>Only {@code INSERT} and {@code DELETE} lines are converted; everything else
 * (DDL, comments, blank lines, bare {@code ;}) yields {@code null} so callers can
 * skip it. {@link Dialect#H2} is a pass-through to avoid regressing the working
 * H2/xiaoya path.
 */
public final class H2SqlConverter {

    public enum Dialect { H2, MYSQL, POSTGRESQL }

    private static final String TIMESTAMP_PREFIX = "TIMESTAMP WITH TIME ZONE ";

    // H2 export column order (from the CREATE CACHED TABLE DDL). Using explicit
    // column lists removes any dependence on the target table's physical column
    // order — important for META where H2 and Flyway orderings differ (movie_id
    // is last in the export but third in the Flyway schema).
    private static final String[] MOVIE_COLS = {
            "id", "actors", "country", "cover", "db_score", "description",
            "directors", "editors", "genre", "language", "name", "year"};
    private static final String[] META_COLS = {
            "id", "disabled", "name", "path", "score", "site_id", "tid", "time",
            "tm_id", "tmdb_id", "type", "year", "movie_id"};
    private static final String[] ALIAS_COLS = {"name", "alias", "movie_id"};

    private static final Pattern INSERT_TABLE =
            Pattern.compile("^INSERT\\s+INTO\\s+\"PUBLIC\"\\.\"(MOVIE|META|ALIAS)\"\\s+VALUES");
    private static final Pattern PUBLIC_TABLE =
            Pattern.compile("\"PUBLIC\"\\.\"(MOVIE|META|ALIAS)\"");
    private static final Pattern TRAILING_TZ = Pattern.compile("\\+\\d\\d'$");

    private H2SqlConverter() {
    }

    /** Resolve the active dialect from Spring profiles (mysql / postgresql), defaulting to H2. */
    public static Dialect detect(Environment env) {
        if (env == null) {
            return Dialect.H2;
        }
        if (env.matchesProfiles("mysql")) {
            return Dialect.MYSQL;
        }
        if (env.matchesProfiles("postgresql")) {
            return Dialect.POSTGRESQL;
        }
        return Dialect.H2;
    }

    /**
     * Convert a single H2-dialect line into a target-dialect statement, or {@code null}
     * if the line is not an INSERT/DELETE (DDL / comment / blank).
     */
    public static String convert(String line, Dialect dialect) {
        if (dialect == Dialect.H2 || line == null) {
            return line;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String sql;
        if (trimmed.startsWith("INSERT INTO \"PUBLIC\".")) {
            sql = convertInsert(trimmed, dialect);
        } else if (trimmed.startsWith("DELETE FROM \"PUBLIC\".")) {
            sql = convertDelete(trimmed, dialect);
        } else {
            return null;
        }
        if (sql == null) {
            return null;
        }
        sql = sql.trim();
        // strip the trailing statement separator — JDBC execute()/batchUpdate() on
        // MySQL rejects a trailing ';' unless allowMultiQueries is on.
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }

    private static String convertInsert(String line, Dialect dialect) {
        String transformed = transformLiterals(line, dialect);
        Matcher m = INSERT_TABLE.matcher(transformed);
        if (!m.find()) {
            return null;
        }
        String table = m.group(1).toLowerCase();
        String columns = columnList(table, dialect);
        return m.replaceFirst(Matcher.quoteReplacement("INSERT INTO " + table + " " + columns + " VALUES"));
    }

    private static String convertDelete(String line, Dialect dialect) {
        String transformed = transformLiterals(line, dialect);
        Matcher m = PUBLIC_TABLE.matcher(transformed);
        if (!m.find()) {
            return null;
        }
        return m.replaceFirst(Matcher.quoteReplacement(m.group(1).toLowerCase()));
    }

    private static String columnList(String table, Dialect dialect) {
        String[] cols;
        switch (table) {
            case "movie" -> cols = MOVIE_COLS;
            case "meta" -> cols = META_COLS;
            case "alias" -> cols = ALIAS_COLS;
            default -> cols = new String[0];
        }
        String yearQuoted = dialect == Dialect.MYSQL ? "`year`" : "\"year\"";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("year".equals(cols[i]) ? yearQuoted : cols[i]);
        }
        return sb.append(")").toString();
    }

    /**
     * Walk the line once, converting {@code U&'...'} escapes to raw UTF-8 and dropping
     * the {@code TIMESTAMP WITH TIME ZONE } introducer (plus the trailing {@code +NN}
     * offset, which neither MySQL DATETIME nor the tz-naive PG timestamp column want).
     * Normal single-quoted literals are copied verbatim so data containing the trigger
     * tokens can never be mis-transformed.
     */
    private static String transformLiterals(String s, Dialect dialect) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (s.startsWith(TIMESTAMP_PREFIX, i)) {
                i += TIMESTAMP_PREFIX.length();
                if (i < n && s.charAt(i) == '\'') {
                    int end = readQuoted(s, i);
                    out.append(stripTzOffset(s.substring(i, end)));
                    i = end;
                    continue;
                }
                out.append(TIMESTAMP_PREFIX);
                continue;
            }
            if (s.startsWith("U&'", i)) {
                int end = decodeUnicodeString(s, i + 2, out);
                i = end;
                continue;
            }
            char c = s.charAt(i);
            if (c == '\'') {
                int end = readQuoted(s, i);
                out.append(s, i, end);
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Read a normal SQL string literal starting at {@code start} (a {@code '}), honoring {@code ''}. */
    private static int readQuoted(String s, int start) {
        int j = start + 1;
        int n = s.length();
        while (j < n) {
            char c = s.charAt(j);
            if (c == '\'') {
                if (j + 1 < n && s.charAt(j + 1) == '\'') {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return j;
    }

    /**
     * Decode a {@code U&'...'} body (the {@code U&} prefix already consumed, {@code start}
     * points at the opening {@code '}); append the decoded value as a normal single-quoted
     * literal (embedded quotes doubled). Returns the index past the closing quote.
     */
    private static int decodeUnicodeString(String s, int start, StringBuilder out) {
        int n = s.length();
        StringBuilder decoded = new StringBuilder();
        int j = start + 1;
        while (j < n) {
            char c = s.charAt(j);
            if (c == '\\' && j + 1 < n) {
                char nx = s.charAt(j + 1);
                if (nx == '\'') {
                    decoded.append('\'');
                    j += 2;
                    continue;
                }
                if (nx == '\\') {
                    decoded.append('\\');
                    j += 2;
                    continue;
                }
                if (j + 5 <= n && isHex(s, j + 1, j + 5)) {
                    decoded.append((char) Integer.parseInt(s.substring(j + 1, j + 5), 16));
                    j += 5;
                    continue;
                }
                decoded.append('\\');
                j++;
                continue;
            }
            if (c == '\'') {
                j++;
                break;
            }
            decoded.append(c);
            j++;
        }
        out.append("'").append(decoded.toString().replace("'", "''")).append("'");
        return j;
    }

    private static boolean isHex(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    /** Drop a trailing {@code +NN} tz offset from an already-quoted timestamp literal. */
    private static String stripTzOffset(String quoted) {
        return TRAILING_TZ.matcher(quoted).replaceFirst("'");
    }
}
