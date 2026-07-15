package nlfilterlab;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

final class ParserCompatibility {
    enum Status {
        MATCHED,
        MISMATCH,
        SOURCE_MISSING,
        BASELINE_MISSING
    }

    record Entry(String name, Path source, String expected, String actual, Status status) {
    }

    record Report(String baselineDate, List<Entry> entries) {
        boolean hasMismatch() {
            return entries.stream().anyMatch(entry -> entry.status == Status.MISMATCH ||
                    entry.status == Status.BASELINE_MISSING);
        }

        boolean sourceAvailable() {
            return entries.stream().anyMatch(entry -> entry.status != Status.SOURCE_MISSING);
        }

        String statusName() {
            if (hasMismatch()) return "mismatch";
            if (!sourceAvailable()) return "source-missing";
            if (entries.stream().anyMatch(entry -> entry.status == Status.SOURCE_MISSING)) return "source-partial";
            return "matched";
        }
    }

    private static final List<String> SOURCE_NAMES = List.of(
            "EasyRewriter.java",
            "JavaPattern.java",
            "JavaMatcher.java",
            "NestPattern.java",
            "NestMatcher.java"
    );

    private ParserCompatibility() {
    }

    static Report inspect(Path repositoryRoot, Path labRoot) {
        Properties baseline = new Properties();
        Path baselinePath = labRoot.resolve("parser-baseline.properties");
        if (Files.isRegularFile(baselinePath)) {
            try (InputStream input = Files.newInputStream(baselinePath)) {
                baseline.load(input);
            } catch (IOException ignored) {
                // 各エントリーをBASELINE_MISSINGとして返す。
            }
        }

        Path sourceRoot = repositoryRoot.getParent().resolve("src/dareka");
        List<Entry> entries = new ArrayList<>();
        for (String name : SOURCE_NAMES) {
            Path source = switch (name) {
                case "EasyRewriter.java" -> sourceRoot.resolve("processor/impl/EasyRewriter.java");
                default -> sourceRoot.resolve("common/regex").resolve(name);
            };
            String expected = baseline.getProperty(name);
            if (expected == null || expected.isBlank()) {
                entries.add(new Entry(name, source, expected, null, Status.BASELINE_MISSING));
            } else if (!Files.isRegularFile(source)) {
                entries.add(new Entry(name, source, expected, null, Status.SOURCE_MISSING));
            } else {
                String actual;
                try {
                    actual = sha256(source);
                } catch (IOException exception) {
                    entries.add(new Entry(name, source, expected, null, Status.SOURCE_MISSING));
                    continue;
                }
                entries.add(new Entry(name, source, expected, actual,
                        expected.equalsIgnoreCase(actual) ? Status.MATCHED : Status.MISMATCH));
            }
        }
        return new Report(baseline.getProperty("baselineDate", "unknown"), List.copyOf(entries));
    }

    static String toJson(Report report) {
        StringBuilder json = new StringBuilder("{\"status\":").append(Json.quote(report.statusName()))
                .append(",\"baselineDate\":").append(Json.quote(report.baselineDate()))
                .append(",\"entries\":[");
        boolean first = true;
        for (Entry entry : report.entries()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"name\":").append(Json.quote(entry.name()))
                    .append(",\"source\":").append(Json.quote(entry.source().toAbsolutePath().toString()))
                    .append(",\"expected\":").append(Json.quote(entry.expected()))
                    .append(",\"actual\":").append(Json.quote(entry.actual()))
                    .append(",\"status\":").append(Json.quote(entry.status().name().toLowerCase())).append('}');
        }
        return json.append("]}").toString();
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
