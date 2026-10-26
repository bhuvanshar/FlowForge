package com.flowforge.support;

import java.util.List;

/** JSON builders for workflow definitions used by the trigger integration tests. */
public final class Workflows {

    private Workflows() {
    }

    public static String node(String id, String type, String config) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"typeVersion\":1,\"name\":\"" + id
                + "\",\"position\":{\"x\":0,\"y\":0},\"configuration\":" + config + "}";
    }

    public static String edge(String source, String target) {
        return "{\"id\":\"" + source + "-" + target + "\",\"source\":\"" + source + "\",\"sourceHandle\":\"main\","
                + "\"target\":\"" + target + "\",\"targetHandle\":\"main\"}";
    }

    public static String definition(List<String> nodes, List<String> edges) {
        return "{\"schemaVersion\":1,\"nodes\":[" + String.join(",", nodes) + "],\"edges\":[" + String.join(",", edges)
                + "]}";
    }

    /** {@code trigger -> log}. */
    public static String withTrigger(String type, String config) {
        return definition(List.of(node("t", type, config), node("log", "core.logger", "{\"message\":\"got it\"}")),
                List.of(edge("t", "log")));
    }

    public static String webhook(String method, String authentication, String inputMode) {
        return withTrigger("core.webhookTrigger", "{\"method\":\"" + method + "\",\"authentication\":\""
                + authentication + "\",\"inputMode\":\"" + inputMode + "\"}");
    }

    public static String schedule(String cron, String timezone, String policy) {
        return withTrigger("core.scheduleTrigger", "{\"cron\":\"" + cron + "\",\"timezone\":\"" + timezone
                + "\",\"missedRunPolicy\":\"" + policy + "\"}");
    }

    public static String manual() {
        return withTrigger("core.manualTrigger", "{}");
    }
}
