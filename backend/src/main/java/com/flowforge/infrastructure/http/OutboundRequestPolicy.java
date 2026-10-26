package com.flowforge.infrastructure.http;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Decides which URLs and addresses outbound requests may use. Checked twice: on the URL before sending (and for every
 * redirect), and on every resolved address right before connecting ({@link GuardedDnsResolver}), so DNS rebinding cannot
 * swap in a forbidden address between check and use.
 */
@Component
public class OutboundRequestPolicy {

    public static final String INVALID_URL = "INVALID_URL";
    public static final String HOST_NOT_ALLOWED = "HTTP_HOST_NOT_ALLOWED";
    public static final String PORT_NOT_ALLOWED = "HTTP_PORT_NOT_ALLOWED";
    public static final String ADDRESS_BLOCKED = "HTTP_ADDRESS_BLOCKED";

    private final HttpProperties properties;
    private final List<String> allowed;

    public OutboundRequestPolicy(HttpProperties properties) {
        this.properties = properties;
        this.allowed = properties.allowedHosts().stream().map(OutboundRequestPolicy::normalize).toList();
    }

    /** Checks scheme, credentials, host allowlist and port. Throws {@link OutboundPolicyException}. */
    public void checkUrl(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.isOpaque()) {
            throw new OutboundPolicyException(INVALID_URL, "The URL must be absolute, e.g. https://api.example.com/x.");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new OutboundPolicyException(INVALID_URL, "Only http and https URLs are allowed.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new OutboundPolicyException(INVALID_URL, "Credentials in the URL are not allowed; use a header.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new OutboundPolicyException(INVALID_URL, "The URL has no host.");
        }
        if (!isHostAllowed(host)) {
            throw new OutboundPolicyException(HOST_NOT_ALLOWED, "Host '" + host + "' is not on the outbound allowlist "
                    + "(flowforge.http.allowed-hosts).");
        }
        int port = uri.getPort() != -1 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        if (!properties.allowedPorts().isEmpty() && !properties.allowedPorts().contains(port)) {
            throw new OutboundPolicyException(PORT_NOT_ALLOWED, "Port " + port + " is not allowed.");
        }
    }

    public boolean isHostAllowed(String host) {
        String candidate = normalize(host);
        for (String rule : allowed) {
            if (rule.startsWith("*.")) {
                String suffix = rule.substring(1); // ".example.com"
                if (candidate.endsWith(suffix) && candidate.length() > suffix.length()) {
                    return true;
                }
            } else if (rule.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Throws {@link BlockedAddressException} if {@code address} may not be connected to. */
    public void checkAddress(String host, InetAddress address) throws BlockedAddressException {
        if (!properties.allowPrivateAddresses() && isNonPublic(address)) {
            throw new BlockedAddressException("Host '" + host + "' resolves to a non-public address ("
                    + address.getHostAddress() + "), which is blocked.");
        }
    }

    /** Loopback, private, link-local, CGNAT, multicast, documentation, reserved and similar ranges. */
    public static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isNonPublicV4(b);
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return true; // fc00::/7 unique local
            }
            if (first == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
                return true; // 2001:db8::/32 documentation
            }
            boolean zeroPrefix = true;
            for (int i = 0; i < 10; i++) {
                zeroPrefix &= b[i] == 0;
            }
            boolean mapped = zeroPrefix && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
            boolean compatible = zeroPrefix && b[10] == 0 && b[11] == 0;
            boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff
                    && (b[3] & 0xff) == 0x9b;
            if (mapped || compatible || nat64) {
                return isNonPublicV4(new byte[] {b[12], b[13], b[14], b[15]});
            }
        }
        return false;
    }

    private static boolean isNonPublicV4(byte[] b) {
        int a = b[0] & 0xff;
        int c = b[1] & 0xff;
        return a == 0 || a == 10 || a == 127 || a >= 224                       // this-network, private, loopback, multicast+reserved
                || (a == 100 && c >= 64 && c <= 127)                             // 100.64.0.0/10 CGNAT
                || (a == 169 && c == 254)                                       // link-local, cloud metadata
                || (a == 172 && c >= 16 && c <= 31) || (a == 192 && c == 168)   // private
                || (a == 192 && c == 0 && (b[2] & 0xff) == 0)                   // 192.0.0.0/24
                || (a == 192 && c == 0 && (b[2] & 0xff) == 2)                   // 192.0.2.0/24 documentation
                || (a == 198 && (c == 18 || c == 19))                           // 198.18.0.0/15 benchmarking
                || (a == 198 && c == 51 && (b[2] & 0xff) == 100)                // 198.51.100.0/24 documentation
                || (a == 203 && c == 0 && (b[2] & 0xff) == 113);                // 203.0.113.0/24 documentation
    }

    static String normalize(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.startsWith("*.")) {
            return "*." + IDN.toASCII(h.substring(2), IDN.ALLOW_UNASSIGNED);
        }
        return h.contains(":") ? h : IDN.toASCII(h, IDN.ALLOW_UNASSIGNED);
    }

    /** Thrown from DNS resolution so the HTTP client aborts before connecting. */
    public static class BlockedAddressException extends UnknownHostException {

        public BlockedAddressException(String message) {
            super(message);
        }
    }
}
