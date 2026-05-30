package com.example.orchestrator.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TemplateRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    public static String render(String template, Map<String, String> vars) {
        if (template == null || vars == null || vars.isEmpty()) return template;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    vars.getOrDefault(key, matcher.group(0))));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
