package nlfilterlab;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

public final class NlFilterLabTests {
    private static final String HEADER = "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)\n";
    private static int passed;

    private NlFilterLabTests() {
    }

    public static void main(String[] args) throws Exception {
        Path repository = Path.of(System.getProperty("nlfilterlab.repository", ".")).toAbsolutePath().normalize();
        Path temporary = repository.resolve(".cache/nlfilter-lab/tests");
        Files.createDirectories(temporary);

        run("追跡中フィルターをエラーなく解析", () -> trackedFiltersParse(repository));
        run("未閉鎖ブロックを検出", () -> unclosedBlock(temporary));
        run("不正なJava正規表現を検出", () -> invalidPattern(temporary));
        run("未知セクションを検出", () -> unknownSection(temporary));
        run("EachLine行数不一致を検出", () -> eachLineMismatch(temporary));
        run("EachLineを行ごとに適用", () -> eachLine(repository, temporary));
        run("URLとContent-Typeの非対象を除外", () -> contextSelection(repository, temporary));
        run("idGroupの5状態を切り替え", () -> cacheVariants(repository));
        run("idGroup第2値から動画IDを補完", () -> idGroupFallback(repository, temporary));
        run("StyleとScriptをhead/bodyへ挿入", () -> appendSections(repository, temporary));
        run("Replace内のCRLFを維持", () -> crlfReplacement(repository, temporary));
        run("末尾空白付きセクションのtrimモード", () -> trimMode(repository, temporary));
        run("freeSpaceをJavaScript数値へ置換", () -> freeSpaceVariable(repository));
        run("追跡ファイルを辞書順で列挙", () -> trackedOrder(repository));
        run("ローカルサーバーのOrigin・本文・パス境界", () -> serverBoundaries(repository));

        System.out.println("PASS: " + passed + " tests");
    }

    private static void trackedFiltersParse(Path repository) throws Exception {
        FilterParser parser = new FilterParser();
        ParseResult all = new ParseResult();
        List<Path> files = RepositoryFilters.tracked(repository);
        for (Path file : files) all.merge(parser.parse(file));
        assertEquals(8, files.size(), "追跡フィルター数");
        assertTrue(!all.hasErrors(), "既存フィルターにエラーがない");
        assertEquals(40, all.rules.size(), "既存ルール数");
    }

    private static void unclosedBlock(Path temporary) throws Exception {
        Path file = write(temporary, "unclosed.txt", HEADER + "[Replace]\nName = x\nURL = example\\.com/\nMatch<\n(foo\n");
        ParseResult result = new FilterParser().parse(file);
        assertDiagnostic(result, "unclosed-block");
    }

    private static void invalidPattern(Path temporary) throws Exception {
        Path file = write(temporary, "invalid-pattern.txt", HEADER +
                "[Replace]\nName = x\nURL = example\\.com/\nMatch<\n([\n>\nReplace<\nx\n>\n");
        ParseResult result = new FilterParser().parse(file);
        assertDiagnostic(result, "pattern");
    }

    private static void eachLine(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "each-line.txt", HEADER +
                "[Replace]\nName = each\nURL = example\\.com/\nEachLine = TRUE\nMulti = TRUE\n" +
                "Match<\ncat\ndog\n>\nReplace<\n猫\n犬\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        SimulationResult result = simulate(repository, parsed.rules, "cat dog cat", "https://example.com/", "text/html",
                FilterRule.CacheState.NONE);
        assertEquals("猫 犬 猫", result.rendered, "EachLine変換結果");
    }

    private static void unknownSection(Path temporary) throws Exception {
        Path file = write(temporary, "unknown-section.txt", HEADER + "[Replcae]\n");
        assertDiagnostic(new FilterParser().parse(file), "unknown-section");
    }

    private static void eachLineMismatch(Path temporary) throws Exception {
        Path file = write(temporary, "each-line-mismatch.txt", HEADER +
                "[Replace]\nName = mismatch\nURL = example\\.com/\nEachLine = TRUE\n" +
                "Match<\na\nb\n>\nReplace<\nx\n>\n");
        assertDiagnostic(new FilterParser().parse(file), "each-line-count");
    }

    private static void contextSelection(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "context.txt", HEADER +
                "[Replace]\nName = context\nURL = example\\.com/watch/\nContentType = text/html\n" +
                "Match<\nold\n>\nReplace<\nnew\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        SimulationResult wrongUrl = simulate(repository, parsed.rules, "old", "https://example.com/search/", "text/html",
                FilterRule.CacheState.NONE);
        SimulationResult wrongType = simulate(repository, parsed.rules, "old", "https://example.com/watch/1", "application/json",
                FilterRule.CacheState.NONE);
        assertEquals("old", wrongUrl.rendered, "URL非対象");
        assertEquals("old", wrongType.rendered, "Content-Type非対象");
    }

    private static void cacheVariants(Path repository) {
        ParseResult parsed = new FilterParser().parse(repository.resolve("20_watchFilter.txt"));
        FilterRule colorRule = parsed.rules.stream().filter(rule -> "キャッシュ済動画のリンク色変更(watch)".equals(rule.name))
                .findFirst().orElseThrow();
        String html = "<a href=\"/watch/sm9\">title</a>";
        assertContains(simulate(repository, List.of(colorRule), html, "https://www.nicovideo.jp/watch/sm9", "text/html",
                FilterRule.CacheState.NORMAL).rendered, "#C00000", "通常色");
        assertContains(simulate(repository, List.of(colorRule), html, "https://www.nicovideo.jp/watch/sm9", "text/html",
                FilterRule.CacheState.ECONOMY).rendered, "#C08000", "エコノミー色");
        assertContains(simulate(repository, List.of(colorRule), html, "https://www.nicovideo.jp/watch/sm9", "text/html",
                FilterRule.CacheState.DMC).rendered, "#008000", "DMC色");
        assertContains(simulate(repository, List.of(colorRule), html, "https://www.nicovideo.jp/watch/sm9", "text/html",
                FilterRule.CacheState.DMC_ECONOMY).rendered, "#808000", "DMCエコノミー色");
        assertEquals(html, simulate(repository, List.of(colorRule), html, "https://www.nicovideo.jp/watch/sm9", "text/html",
                FilterRule.CacheState.NONE).rendered, "キャッシュなし");
    }

    private static void idGroupFallback(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "id-group-fallback.txt", HEADER +
                "[Replace]\nName = fallback\nURL = example\\.com/\nidGroup = 1,2\n" +
                "Match<\n(?:video/(sm\\d+)|thumb/(\\d+))\n>\nReplace<\n<eachSmid>-cached\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        SimulationResult result = simulate(repository, parsed.rules, "thumb/9", "https://example.com/", "text/html",
                FilterRule.CacheState.NORMAL);
        assertEquals("sm9-cached", result.rendered, "idGroup第2値補完");
    }

    private static void appendSections(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "append.txt", HEADER +
                "[Style]\nName = style\nURL = example\\.com/\nAppend<\n.test { color: red; }\n>\n" +
                "[Script]\nName = script\nURL = example\\.com/\nAppend<\nwindow.tested = true;\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        SimulationResult result = simulate(repository, parsed.rules, "<html><head></head><body></body></html>",
                "https://example.com/", "text/html", FilterRule.CacheState.NONE);
        assertContains(result.rendered, "<style type=\"text/css\">", "Styleタグ");
        assertTrue(result.rendered.indexOf(".test { color: red; }") < result.rendered.indexOf("</head>"), "Style挿入位置");
        assertTrue(result.rendered.indexOf("window.tested = true;") < result.rendered.indexOf("</body>"), "Script挿入位置");
    }

    private static void crlfReplacement(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "crlf.txt", HEADER +
                "[Replace]\nName = crlf\nURL = example\\.com/\nMatch<\nmarker\n>\nReplace<\nfirst\nsecond<CRLF>third\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        SimulationResult result = simulate(repository, parsed.rules, "marker", "https://example.com/", "text/html",
                FilterRule.CacheState.NONE);
        assertEquals("first\r\nsecond\r\nthird", result.rendered, "CRLF変換");
    }

    private static void trackedOrder(Path repository) throws Exception {
        List<Path> files = RepositoryFilters.tracked(repository);
        List<String> actual = files.stream().map(path -> path.getFileName().toString()).toList();
        List<String> sorted = new ArrayList<>(actual);
        sorted.sort(String::compareTo);
        assertEquals(sorted, actual, "辞書順");
    }

    private static void serverBoundaries(Path repository) throws Exception {
        Path labRoot = Path.of(System.getProperty("nlfilterlab.root", "tools/nlfilter-lab")).toAbsolutePath().normalize();
        LabServer server = new LabServer(repository, labRoot, 0);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.port();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> config = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, config.statusCode(), "config status");

            HttpResponse<String> sandboxOrigin = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config"))
                            .header("Origin", "null").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, sandboxOrigin.statusCode(), "sandbox Origin許可");
            assertEquals("null", sandboxOrigin.headers().firstValue("Access-Control-Allow-Origin").orElse(""),
                    "sandbox CORS応答");

            HttpResponse<String> foreign = client.send(HttpRequest.newBuilder(URI.create(base + "/api/render"))
                            .header("Origin", "https://example.com")
                            .POST(HttpRequest.BodyPublishers.ofString("fixture=watch"))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, foreign.statusCode(), "外部Origin拒否");
            assertTrue(foreign.headers().firstValue("Access-Control-Allow-Origin").isEmpty(), "外部OriginへCORSを許可しない");

            HttpResponse<String> oversized = client.send(HttpRequest.newBuilder(URI.create(base + "/api/render"))
                            .POST(HttpRequest.BodyPublishers.ofString("x".repeat(1024 * 1024 + 1)))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(413, oversized.statusCode(), "大きすぎる本文を拒否");

            HttpResponse<String> traversal = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/local/%2e%2e/config.properties")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, traversal.statusCode(), "localパストラバーサル拒否");
        } finally {
            server.stop();
        }
    }

    private static void trimMode(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "trim-mode.txt", HEADER +
                "[Replace] \n  Name = trim  \n  URL = example\\.com/  \n  Match<  \n  foo  \n  >  \n  Replace<  \n  bar  \n  >  \n");
        ParseResult parsed = new FilterParser().parse(file);
        assertTrue(!parsed.hasErrors(), "trimモード構文");
        SimulationResult result = simulate(repository, parsed.rules, "foo", "https://example.com/", "text/html",
                FilterRule.CacheState.NONE);
        assertEquals("bar", result.rendered, "trimモード置換");
    }

    private static void freeSpaceVariable(Path repository) {
        ParseResult parsed = new FilterParser().parse(repository.resolve("05_topBarFilter.txt"));
        SimulationResult result = simulate(repository, parsed.rules, "<html><head></head><body></body></html>",
                "https://www.nicovideo.jp/watch/sm9", "text/html", FilterRule.CacheState.NONE);
        assertContains(result.rendered, "if (128.0 < 3)", "freeSpace数値");
    }

    private static SimulationResult simulate(Path repository, List<FilterRule> rules, String content, String url,
            String contentType, FilterRule.CacheState state) {
        return new FilterEngine(repository).simulate(rules,
                new SimulationRequest("test", url, contentType, 200, state, false), content);
    }

    private static Path write(Path directory, String name, String content) throws Exception {
        Path file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static void assertDiagnostic(ParseResult result, String code) {
        assertTrue(result.diagnostics.stream().anyMatch(diagnostic -> diagnostic.code().equals(code)), "診断 " + code);
    }

    private static void assertContains(String actual, String expected, String label) {
        assertTrue(actual.contains(expected), label + ": " + actual);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void run(String name, CheckedRunnable runnable) throws Exception {
        runnable.run();
        passed++;
        System.out.println("PASS " + name);
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
