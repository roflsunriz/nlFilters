package nlfilterlab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FilterEngine {
    private static final Pattern CACHE_SPLIT = Pattern.compile(
            "^([\\s\\S]*?)\\n*<\\$>\\n*([\\s\\S]*)<\\$>\\n*([\\s\\S]*)<\\$>\\n*([\\s\\S]*)$");
    private static final Pattern INLINE_CACHE_SPLIT = Pattern.compile(
            "^([\\s\\S]*?)\\n*<([^<>]*?)\\$\\$([^<>]*?)\\$\\$([^<>]*?)\\$\\$([^<>]*?)>\\n*([\\s\\S]*)$");
    private static final Pattern LEGACY_CACHE_SPLIT = Pattern.compile(
            "^([\\s\\S]*?)\\n*<(\\w*)\\$(\\w*)>\\n*([\\s\\S]*)$");
    private static final Pattern URL_MACRO = Pattern.compile("(?<!\\\\)(?:\\\\\\\\)*\\$URL(\\d+)(?!\\d)");
    private static final Pattern TS_MACRO = Pattern.compile("(?<!\\\\)(?:\\\\\\\\)*\\$TS\\(([^?)]*)(\\?[^)]+)?\\)");
    private static final Pattern NL_VARIABLE = Pattern.compile("<nlVar:([^>]+)>");

    private final Path repositoryRoot;
    private final Path localRoot;

    FilterEngine(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
        this.localRoot = repositoryRoot.getParent().resolve("local").normalize();
    }

    SimulationResult simulate(List<FilterRule> rules, SimulationRequest request, String content) {
        SimulationResult result = new SimulationResult(content);
        List<FilterRule> styles = new ArrayList<>();
        List<FilterRule> scripts = new ArrayList<>();
        List<FilterRule> delayed = new ArrayList<>();

        for (FilterRule rule : rules) {
            if (!rule.isResponseRule() || !matchesContext(rule, request, result.rendered)) {
                continue;
            }
            if (!rule.simulationSupported) {
                result.traces.add(new SimulationResult.Trace(rule.identifier(), rule.section.label(), 0,
                        "未対応マクロを含むため疑似適用をスキップ"));
                continue;
            }
            if (rule.section == FilterRule.Section.REPLACE) {
                if (rule.replaceDelay) {
                    delayed.add(rule);
                } else {
                    applyReplace(rule, request, result);
                }
            } else if (rule.section == FilterRule.Section.STYLE) {
                styles.add(rule);
            } else if (rule.section == FilterRule.Section.SCRIPT) {
                scripts.add(rule);
            }
        }

        appendCollected(styles, "style", "</head>", request, result);
        appendCollected(scripts, "script", "</body>", request, result);
        for (FilterRule rule : delayed) {
            applyReplace(rule, request, result);
        }
        return result;
    }

    private static boolean matchesContext(FilterRule rule, SimulationRequest request, String content) {
        if (rule.urlPattern == null || !rule.urlPattern.matcher(request.url()).lookingAt()) {
            return false;
        }
        if (rule.contentType != null && !rule.contentType.find(request.contentType())) {
            return false;
        }
        if (rule.requireHeader != null && !rule.requireHeader.find(mockRequestHeader(request))) {
            return false;
        }
        if (rule.statusCodes != null) {
            boolean accepted = false;
            for (int statusCode : rule.statusCodes) {
                accepted |= statusCode == request.statusCode();
            }
            if (!accepted) {
                return false;
            }
        } else if (request.statusCode() != 200 && request.statusCode() != 403 &&
                request.statusCode() != 404 && request.statusCode() != 503) {
            return false;
        }
        return rule.require == null || rule.require.find(content);
    }

    private static String mockRequestHeader(SimulationRequest request) {
        return "GET " + request.url() + " HTTP/1.1\r\n" +
                "User-Agent: nlFilter-Lab/1.0\r\n" +
                "Accept: " + request.contentType() + "\r\n";
    }

    private void applyReplace(FilterRule rule, SimulationRequest request, SimulationResult result) {
        int replacedCount = 0;
        String working = result.rendered;
        for (int index = 0; index < rule.matches.size(); index++) {
            Pattern pattern = rule.matches.get(index);
            Matcher matcher = pattern.matcher(working);
            if (!matcher.find()) {
                continue;
            }
            StringBuffer output = new StringBuffer(working.length() + 256);
            int currentCount = 0;
            do {
                String raw = index < rule.replacements.size() ? rule.replacements.get(index) : "";
                String replacement = selectCacheVariant(rule, matcher, raw, request.cacheState());
                if (replacement != null) {
                    replacement = expandStaticMacros(rule, request, replacement);
                    try {
                        matcher.appendReplacement(output,
                                rule.replaceOnly ? Matcher.quoteReplacement(replacement) : replacement);
                        currentCount++;
                    } catch (RuntimeException exception) {
                        result.diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, rule.file,
                                rule.sectionLine, "replacement", rule.identifier() + " の置換に失敗しました: " +
                                exception.getMessage()));
                        matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group()));
                    }
                } else {
                    matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group()));
                }
                if (!rule.multi) {
                    break;
                }
            } while (matcher.find());
            matcher.appendTail(output);
            if (currentCount > 0) {
                working = output.toString();
                replacedCount += currentCount;
            }
        }
        result.rendered = working;
        result.traces.add(new SimulationResult.Trace(rule.identifier(), rule.section.label(), replacedCount,
                replacedCount == 0 ? "Match または疑似キャッシュ条件が非該当" : "本文を置換"));
    }

    private static String selectCacheVariant(FilterRule rule, Matcher contentMatcher, String raw,
            FilterRule.CacheState cacheState) {
        if (rule.idGroup <= 0) {
            return raw;
        }
        String videoId;
        String alternateId = null;
        try {
            videoId = contentMatcher.group(rule.idGroup);
            if (rule.idGroup2 > 0) {
                alternateId = contentMatcher.group(rule.idGroup2);
            }
        } catch (RuntimeException exception) {
            return null;
        }
        if ((videoId == null || videoId.matches("\\d{10,}")) && alternateId != null &&
                alternateId.matches("\\d+")) {
            // 実環境はキャッシュ索引から sm/so/nm 等を解決する。Labはキャッシュ状態が
            // URL全体で固定なので、分岐確認用の決定的なsmidへ補完する。
            videoId = "sm" + alternateId;
        }
        if (videoId == null || videoId.isBlank()) {
            return rule.noCache && cacheState == FilterRule.CacheState.NONE ? raw : null;
        }
        if (rule.noCache) {
            return cacheState == FilterRule.CacheState.NONE ? raw : null;
        }
        if (cacheState == FilterRule.CacheState.NONE) {
            return null;
        }

        String[] variants = splitCacheVariants(raw);
        int variant = switch (cacheState) {
            case NORMAL -> 0;
            case ECONOMY -> 1;
            case DMC -> 2;
            case DMC_ECONOMY -> 3;
            case NONE -> 0;
        };
        return variants[variant].replace("<eachSmid>", videoId);
    }

    private static String[] splitCacheVariants(String raw) {
        String[] variants = {raw, raw, raw, raw};
        Matcher matcher = CACHE_SPLIT.matcher(raw);
        if (matcher.matches()) {
            for (int i = 0; i < 4; i++) {
                variants[i] = matcher.group(i + 1);
            }
            return variants;
        }
        matcher = INLINE_CACHE_SPLIT.matcher(raw);
        if (matcher.matches()) {
            for (int i = 0; i < 4; i++) {
                variants[i] = matcher.group(1) + matcher.group(i + 2) + matcher.group(6);
            }
            return variants;
        }
        matcher = LEGACY_CACHE_SPLIT.matcher(raw);
        if (matcher.matches()) {
            variants[0] = matcher.group(1) + matcher.group(2) + matcher.group(4);
            variants[1] = matcher.group(1) + matcher.group(3) + matcher.group(4);
            variants[2] = variants[0];
            variants[3] = variants[1];
        }
        return variants;
    }

    private String expandStaticMacros(FilterRule rule, SimulationRequest request, String replacement) {
        if (rule.urlPattern != null && replacement.contains("$URL")) {
            Matcher url = rule.urlPattern.matcher(request.url());
            if (url.lookingAt()) {
                Matcher macro = URL_MACRO.matcher(replacement);
                StringBuffer output = new StringBuffer();
                while (macro.find()) {
                    int group = Integer.parseInt(macro.group(1));
                    String value = group <= url.groupCount() && url.group(group) != null ? url.group(group) : macro.group();
                    macro.appendReplacement(output, Matcher.quoteReplacement(value));
                }
                replacement = macro.appendTail(output).toString();
            }
        }

        Matcher timestamp = TS_MACRO.matcher(replacement);
        StringBuffer timestampOutput = new StringBuffer();
        while (timestamp.find()) {
            String path = timestamp.group(1);
            String queryPrefix = timestamp.group(2) == null ? "?" : timestamp.group(2);
            Path target = repositoryRoot.getParent().resolve(path).normalize();
            String value = path;
            if (target.startsWith(repositoryRoot.getParent()) && Files.isRegularFile(target)) {
                try {
                    value = path + queryPrefix + (Files.getLastModifiedTime(target).toMillis() / 1000L);
                } catch (Exception ignored) {
                    value = path;
                }
            }
            timestamp.appendReplacement(timestampOutput, Matcher.quoteReplacement(value));
        }
        replacement = timestamp.appendTail(timestampOutput).toString();

        Matcher variable = NL_VARIABLE.matcher(replacement);
        StringBuffer variableOutput = new StringBuffer();
        while (variable.find()) {
            String value = switch (variable.group(1)) {
                case "VERSION" -> "nlFilter-Lab";
                default -> "";
            };
            variable.appendReplacement(variableOutput, Matcher.quoteReplacement(value));
        }
        replacement = variable.appendTail(variableOutput).toString();
        replacement = replacement.replace("<freeSpace>", "128.0");

        // 仮想URLを判定に使いつつ、ローカル資産はLabサーバーから読み込ませる。
        replacement = replacement.replaceAll("(?i)https?://www\\.nicovideo\\.jp/local/", "/local/");
        return replacement;
    }

    private void appendCollected(List<FilterRule> rules, String elementName, String marker,
            SimulationRequest request, SimulationResult result) {
        if (rules.isEmpty()) {
            return;
        }
        StringBuilder payload = new StringBuilder();
        for (FilterRule rule : rules) {
            String body = rule.replacements.isEmpty() ? "" :
                    expandStaticMacros(rule, request, rule.replacements.get(0));
            payload.append('<').append(elementName).append(" type=\"")
                    .append(elementName.equals("style") ? "text/css" : "text/javascript")
                    .append("\">\r\n/* ").append(rule.identifier()).append(" */\r\n")
                    .append(body).append("\r\n</").append(elementName).append(">\r\n");
        }
        int position = result.rendered.toLowerCase(Locale.ROOT).indexOf(marker.toLowerCase(Locale.ROOT));
        if (position >= 0) {
            result.rendered = result.rendered.substring(0, position) + payload + result.rendered.substring(position);
            for (FilterRule rule : rules) {
                result.traces.add(new SimulationResult.Trace(rule.identifier(), rule.section.label(), 1,
                        "</" + (elementName.equals("style") ? "head" : "body") + "> の直前へ一括挿入"));
            }
        } else {
            for (FilterRule rule : rules) {
                result.traces.add(new SimulationResult.Trace(rule.identifier(), rule.section.label(), 0,
                        marker + " がないため挿入なし"));
            }
            result.diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, null, 0, "missing-html-marker",
                    marker + " がないため " + elementName + " を挿入できませんでした"));
        }
    }
}
