package com.flowforge.support;

/** JSON bodies for creating credentials in integration tests. */
public final class Credentials {

    private Credentials() {
    }

    public static String bearer(String name, String token, String... allowedHosts) {
        return "{\"type\":\"httpBearer\",\"name\":\"" + name + "\",\"allowedHosts\":" + hosts(allowedHosts)
                + ",\"secret\":{\"token\":\"" + token + "\"}}";
    }

    public static String basic(String name, String user, String password, String... allowedHosts) {
        return "{\"type\":\"httpBasic\",\"name\":\"" + name + "\",\"allowedHosts\":" + hosts(allowedHosts)
                + ",\"fields\":{\"username\":\"" + user + "\"},\"secret\":{\"password\":\"" + password + "\"}}";
    }

    public static String header(String name, String headerName, String value, String... allowedHosts) {
        return "{\"type\":\"httpHeader\",\"name\":\"" + name + "\",\"allowedHosts\":" + hosts(allowedHosts)
                + ",\"fields\":{\"headerName\":\"" + headerName + "\"},\"secret\":{\"value\":\"" + value + "\"}}";
    }

    private static String hosts(String... hosts) {
        return "[\"" + String.join("\",\"", hosts) + "\"]";
    }
}
