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
        switch (command) {
            case "check" -> System.exit(check(repositoryRoot, arguments));
            case "serve" -> serve(repositoryRoot, labRoot, arguments);
            default -> {
                System.err.println("使用方法: nlfilter-lab.ps1 <check|serve> [options]");
                System.exit(2);
            }
        }
    }

    private static int check(Path repositoryRoot, List<String> arguments) throws Exception {
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
        all.diagnostics.stream()
                .sorted((left, right) -> left.display(repositoryRoot).compareTo(right.display(repositoryRoot)))
                .forEach(diagnostic -> System.out.println(diagnostic.display(repositoryRoot)));
        long errors = all.diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).count();
        long warnings = all.diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.WARNING).count();
        System.out.printf("%d files, %d rules, %d errors, %d warnings%n",
                files.size(), all.rules.size(), errors, warnings);
        return errors == 0 ? 0 : 1;
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
