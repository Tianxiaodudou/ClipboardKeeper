package com.tianxiaodudou.clipboardpolice.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 更新日志四路兜底：Release 正文 → jsDelivr 的 CHANGELOG.md（按 tag）→
 * raw.githubusercontent.com → releases.atom 正文。第一个非空结果直接采用。
 */
public final class ChangelogFetcher {

    private static final String REPO = "Tianxiaodudou/ClipboardKeeper";
    private static final String USER_AGENT = "ClipboardKeeper-UpdateChecker";

    private ChangelogFetcher() {
    }

    public static String fetch(String version, String releaseBody) {
        if (!blank(releaseBody)) return releaseBody.trim();

        String markdown = httpGet("https://cdn.jsdelivr.net/gh/" + REPO + "@v" + version + "/CHANGELOG.md");
        String section = section(markdown, version);
        if (!blank(section)) return section;

        markdown = httpGet("https://raw.githubusercontent.com/" + REPO + "/v" + version + "/CHANGELOG.md");
        section = section(markdown, version);
        if (!blank(section)) return section;

        String atom = httpGet("https://github.com/" + REPO + "/releases.atom");
        String body = atomBody(atom);
        return body == null ? "" : body;
    }

    /** 从 Markdown 里取 "## 版本号" 到下一个二级标题之间的内容 */
    static String section(String markdown, String version) {
        if (blank(markdown) || blank(version)) return "";
        String[] lines = markdown.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                boolean matched = trimmed.contains(version);
                if (inside && !matched) break;
                inside = matched;
                continue;
            }
            if (inside) out.append(line).append('\n');
        }
        return out.toString().trim();
    }

    /** releases.atom：取第一条 entry 的 content，退而求其次取 summary */
    static String atomBody(String xml) {
        if (blank(xml)) return "";
        int entry = xml.indexOf("<entry>");
        if (entry < 0) return "";
        int contentStart = xml.indexOf("<content", entry);
        int summaryStart = xml.indexOf("<summary", entry);
        if (contentStart > 0 && (summaryStart < 0 || contentStart < summaryStart)) {
            String text = tagText(xml, contentStart, "content");
            if (!blank(text)) return text;
        }
        if (summaryStart > 0) {
            String text = tagText(xml, summaryStart, "summary");
            if (!blank(text)) return text;
        }
        return "";
    }

    private static String tagText(String xml, int start, String tag) {
        int openEnd = xml.indexOf('>', start);
        if (openEnd < 0) return "";
        if (xml.charAt(openEnd - 1) == '/') return "";
        int close = xml.indexOf("</" + tag + ">", openEnd);
        if (close < 0) return "";
        return clean(xml.substring(openEnd + 1, close));
    }

    /** 去掉 CDATA / HTML 标签并还原常见实体 */
    private static String clean(String raw) {
        String text = raw.replace("<![CDATA[", "").replace("]]>", "");
        text = text.replaceAll("(?s)<[^>]+>", "");
        text = text.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
        return text.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String httpGet(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "*/*");
            if (connection.getResponseCode() != 200) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
