package com.flowforge.infrastructure.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

/**
 * DNS resolution used by the outbound HTTP client. The client connects only to the addresses returned here, so
 * checking them here (not in a separate, earlier lookup) closes the DNS-rebinding gap: the validated answer is the one
 * that is used. If any resolved address is non-public, the whole host is refused rather than silently filtered.
 */
public class GuardedDnsResolver implements DnsResolver {

    private final DnsResolver delegate;
    private final OutboundRequestPolicy policy;

    public GuardedDnsResolver(OutboundRequestPolicy policy) {
        this(SystemDefaultDnsResolver.INSTANCE, policy);
    }

    public GuardedDnsResolver(DnsResolver delegate, OutboundRequestPolicy policy) {
        this.delegate = delegate;
        this.policy = policy;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        if (!policy.isHostAllowed(host)) {
            throw new OutboundRequestPolicy.BlockedAddressException("Host '" + host + "' is not on the allowlist.");
        }
        InetAddress[] addresses = delegate.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        for (InetAddress address : addresses) {
            policy.checkAddress(host, address);
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return delegate.resolveCanonicalHostname(host);
    }
}
