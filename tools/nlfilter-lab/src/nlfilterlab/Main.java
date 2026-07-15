package nlfilterlab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path repositoryRoot = Path.of(System.getProperty("nlfilterlab.repository", ".")).toAbsolutePath().normalize();
        Path labRoot = Path.of(System.getProperty("nlfilterlab.root", "tools/nlfilter-lab")).toAbsolutePath().normalize();
        String command = args.length == 0 ? "check" : args[0];
        List<String> arguments = new ArrayList<>(Arrays.asList(args).subList(Math.min(1, args.length), args.length));
        try {
            switch (command) {
                case "check" -> System.exit(check(repositoryRoot, labRoot, arguments));
                case "source-check" -> System.exit(sourceCheck(repositoryRoot, labRoot, arguments));
                case "headless" -> System.exit(HeadlessRunner.run(repositoryRoot, labRoot, arguments));
                case "serve" -> serve(repositoryRoot, labRoot, arguments);
                default -> {
                    System.err.println("使用方法: nlfilter-lab.ps1 <check|source-check|serve|headless> [options]");
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.exit(2);
        }
    }

    private static int check(Path repositoryRoot, Path labRoot, List<String> arguments) throws Exception {
        boolean json = arguments.remove("--json");
        for (String argument : arguments) {
            if (argument.startsWith("--")) throw new IllegalArgumentException("不明なオプションです: " + argument);
        }
        List<Path> files = arguments.isEmpty()
                ? RepositoryFilters.tracked(repositoryRoot)
                : RepositoryFilters.resolveExplicit(repositoryRoot, arguments);
        ParseResult all = new ParseResult();
        FilterParser parser = new FilterParser();
        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                all.diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, file, 0, "missing-file",
                        "ファイルが存在しません"));
                continue;
            }
            all.merge(parser.parse(file));
        }
        List<Diagnostic> diagnostics = all.diagnostics.stream()
                .sorted((left, right) -> left.display(repositoryRoot).compareTo(right.display(repositoryRoot))).toList();
        long errors = all.diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).count();
        long warnings = all.diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.WARNING).count();
        ParserCompatibility.Report compatibility = ParserCompatibility.inspect(repositoryRoot, labRoot);
        boolean sourceFailure = compatibility.statusName().equals("mismatch") ||
                compatibility.statusName().equals("source-partial");
        if (json) {
            StringBuilder output = new StringBuilder("{\"status\":")
                    .append(Json.quote(errors == 0 && !sourceFailure ? "passed" : "failed"))
                    .append(",\"files\":").append(files.size())
                    .append(",\"rules\":").append(all.rules.size())
                    .append(",\"errors\":").append(errors)
                    .append(",\"warnings\":").append(warnings)
                    .append(",\"parserCompatibility\":").append(ParserCompatibility.toJson(compatibility))
                    .append(",\"diagnostics\":[");
            boolean first = true;
            for (Diagnostic diagnostic : diagnostics) {
                if (!first) output.append(',');
                first = false;
                output.append("{\"severity\":").append(Json.quote(diagnostic.severity().toString()))
                        .append(",\"code\":").append(Json.quote(diagnostic.code()))
                        .append(",\"message\":").append(Json.quote(diagnostic.display(repositoryRoot))).append('}');
            }
            System.out.println(output.append("]}").toString());
        } else {
            diagnostics.forEach(diagnostic -> System.out.println(diagnostic.display(repositoryRoot)));
            printCompatibility(compatibility);
            System.out.printf("%d files, %d rules, %d errors, %d warnings%n",
                    files.size(), all.rules.size(), errors, warnings);
        }
        return errors == 0 && !sourceFailure ? 0 : 1;
    }

    private static int sourceCheck(Path repositoryRoot, Path labRoot, List<String> arguments) {
        boolean json = arguments.remove("--json");
        if (!arguments.isEmpty()) throw new IllegalArgumentException("不明なオプションです: " + arguments.get(0));
        ParserCompatibility.Report report = ParserCompatibility.inspect(repositoryRoot, labRoot);
        if (json) System.out.println(ParserCompatibility.toJson(report));
        else printCompatibility(report);
        return report.statusName().equals("mismatch") || report.statusName().equals("source-partial") ? 1 : 0;
    }

    private static void printCompatibility(ParserCompatibility.Report report) {
        System.out.println("parser source: " + report.statusName() + " (baseline " + report.baselineDate() + ")");
        for (ParserCompatibility.Entry entry : report.entries()) {
            if (entry.status() != ParserCompatibility.Status.MATCHED) {
                System.out.println("  " + entry.status().name().toLowerCase() + " " + entry.source());
            }
        }
    }

    private static void serve(Path repositoryRoot, Path labRoot, List<String> arguments) throws Exception {
        int port = 8765;
        for (int i = 0; i < arguments.size(); i++) {
            if (arguments.get(i).equals("--port") && i + 1 < arguments.size()) {
                port = Integer.parseInt(arguments.get(++i));
            } else {
                throw new IllegalArgumentException("不明なオプションです: " + arguments.get(i));
            }
        }
        LabServer server = new LabServer(repositoryRoot, labRoot, port);
        server.start();
        System.out.println("nlFilter Lab: http://127.0.0.1:" + server.port() + "/");
        System.out.println("終了するには Ctrl+C を押してください。");
    }
}
