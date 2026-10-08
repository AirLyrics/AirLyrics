import com.andsi.airlyrics.lyrics.providers.*;
import com.andsi.airlyrics.lyrics.parser.*;
import org.json.*;
import java.nio.file.*;
import java.time.Instant;
import java.io.*;

/** Live availability probe. Never persists response bodies, lyrics, or credentials. */
public class Benchmark {
    private static final java.lang.reflect.Method CREDIT_CHECK;
    static {
        try {
            CREDIT_CHECK = Class.forName("com.andsi.airlyrics.lyrics.parser.LrcParserKt")
                .getDeclaredMethod("looksLikeLyricsCreditLine", String.class);
            CREDIT_CHECK.setAccessible(true);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    static boolean credit(String text) {
        try { return (boolean) CREDIT_CHECK.invoke(null, text); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    static boolean placeholder(String text) {
        String compact = text.replaceAll("[\\s\\p{Punct}，。！：]", "").toLowerCase(java.util.Locale.ROOT);
        return compact.equals("纯音乐请欣赏") || compact.equals("纯音乐")
            || compact.equals("純音樂請欣賞") || compact.equals("純音樂")
            || compact.equals("instrumental") || compact.equals("instrumentalonly");
    }
    public static void main(String[] args) throws Exception {
        String provider = args[0];
        JSONArray cases = new JSONObject(Files.readString(Path.of(args[1]))).getJSONArray("cases");
        // Native diagnostics are intentionally discarded: reports contain metadata only.
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[2]), StandardOpenOption.CREATE_NEW))) {
            for (int i = 0; i < cases.length(); i++) {
                JSONObject c = cases.getJSONObject(i);
                String title = c.getString("query_title"), artist = c.getString("query_artist"), album = c.optString("album");
                long duration = Math.round(c.getDouble("duration_sec") * 1000);
                JSONObject r = new JSONObject().put("case_id", c.getString("case_id")).put("provider", provider)
                    .put("test_group", c.getString("test_group")).put("started_at", Instant.now().toString())
                    .put("status", "error");
                long start = System.nanoTime();
                try {
                    String raw = switch (provider) {
                        case "netease" -> NeteaseLyricsNative.INSTANCE.fetchBestLyricsJson(title, artist, album, duration, i + 1);
                        case "lrclib" -> LrclibLyricsNative.INSTANCE.fetchBestLyricsJson(title, artist, album, duration, i + 1);
                        case "musixmatch" -> MusixmatchLyricsNative.INSTANCE.fetchBestLyricsJson(title, artist, album, duration, "zh", i + 1);
                        default -> throw new IllegalArgumentException("Unknown provider");
                    };
                    JSONObject root = new JSONObject(raw);
                    if (!root.optBoolean("ok")) {
                        String error = root.optString("error_type", "Unknown");
                        r.put("status", error.equals("NotFound") ? "not_found" : "error").put("error_type", error);
                        // Keep a safe status summary; native details may contain request URLs.
                        var http = java.util.regex.Pattern.compile("\\bHTTP\\s+(?:status\\s+[a-z]+\\s+error\\s*\\()?([1-5][0-9]{2})\\b")
                            .matcher(root.optString("error"));
                        if (http.find()) {
                            int code = Integer.parseInt(http.group(1));
                            r.put("http_status", code).put("error_summary", "HTTP " + code);
                        } else if (root.optString("error").toLowerCase(java.util.Locale.ROOT).contains("timed out")) {
                            r.put("error_summary", "timeout");
                        } else if (error.equals("NetworkError")) {
                            r.put("error_summary", "transport failure");
                        }
                        if (root.has("stage")) r.put("stage", root.getString("stage"));
                    } else {
                        String lrc, translation = null;
                        JSONObject matched = new JSONObject();
                        for (String field : new String[]{"id", "title", "artist", "album", "duration_ms"})
                            if (root.has(field)) matched.put(field, root.get(field));
                        r.put("matched", matched);
                        NativePlainLyricsJsonResult parsed = NativePlainLyricsResultParser.INSTANCE.parse(raw, provider, title, artist, album, duration);
                        translation = parsed.getTranslatedLrc();
                        lrc = provider.equals("netease") ? parsed.getLrc() : parsed.primaryPlainLrc(false);
                        if (provider.equals("netease") && lrc.isBlank() && (translation == null || translation.isBlank())) lrc = parsed.getMergedLrc();
                        var lines = LrcParser.INSTANCE.parseWithTranslation(lrc, translation);
                        long displayable = lines.stream().filter(line -> !line.isMetadata() && (!line.getText().isBlank() || line.hasTranslation())).count();
                        long placeholders = lines.stream().filter(line -> !line.isMetadata() && placeholder(line.getText())).count();
                        long credits = lines.stream().filter(line -> !line.isMetadata() && credit(line.getText())).count();
                        long usable = lines.stream().filter(line -> !line.isMetadata() && !placeholder(line.getText()) && !credit(line.getText())
                            && (!line.getText().isBlank() || line.hasTranslation())).count();
                        r.put("displayable_line_count", displayable).put("placeholder_line_count", placeholders).put("credit_line_count", credits);
                        r.put("line_count", usable).put("status", usable > 0 ? "found" : "not_found");
                        if (usable == 0) r.put("stage", placeholders > 0 ? "instrumental_placeholder" : credits > 0 ? "credits_only" : "no_usable_lines");
                    }
                } catch (Exception | LinkageError e) {
                    // Exception messages can include response content; retain class only.
                    r.put("status", "error").put("error_type", "ParserOrRuntimeError").put("exception_class", e.getClass().getSimpleName());
                }
                r.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000);
                out.println(r); out.flush();
                System.out.printf("%s %d/%d %s %s%n", provider, i + 1, cases.length(), c.getString("case_id"), r.getString("status"));
                int httpStatus = r.optInt("http_status", 0);
                if (r.optString("error_type").equals("RateLimited") || httpStatus == 403 || httpStatus == 429 || httpStatus == 503) {
                    System.out.printf("%s stopped after upstream HTTP/rate refusal%n", provider);
                    break;
                }
                Thread.sleep(1000);
            }
        }
    }
}
