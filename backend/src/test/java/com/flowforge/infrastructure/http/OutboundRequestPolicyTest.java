package com.flowforge.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutboundRequestPolicyTest {

    private static OutboundRequestPolicy policy(List<String> hosts, boolean allowPrivate, List<Integer> ports) {
        return new OutboundRequestPolicy(new HttpProperties(hosts, allowPrivate, ports, null, null, null));
    }

    private final OutboundRequestPolicy policy = policy(List.of("api.example.com", "*.partner.io", "93.184.216.34"),
            false, List.of());

    private String codeOf(String url) {
        try {
            policy.checkUrl(URI.create(url));
            return "OK";
        } catch (OutboundPolicyException e) {
            return e.code();
        }
    }

    @Test
    void allowlistMatchesExactHostsAndSubdomainWildcards() {
        assertThat(codeOf("https://api.example.com/v1")).isEqualTo("OK");
        assertThat(codeOf("https://API.Example.COM./v1")).isEqualTo("OK");
        assertThat(codeOf("https://eu.partner.io/x")).isEqualTo("OK");
        assertThat(codeOf("https://a.b.partner.io/x")).isEqualTo("OK");
        assertThat(codeOf("http://93.184.216.34/")).isEqualTo("OK");
        assertThat(codeOf("https://partner.io/x")).isEqualTo(OutboundRequestPolicy.HOST_NOT_ALLOWED);
        assertThat(codeOf("https://example.com/")).isEqualTo(OutboundRequestPolicy.HOST_NOT_ALLOWED);
        assertThat(codeOf("https://api.example.com.evil.net/")).isEqualTo(OutboundRequestPolicy.HOST_NOT_ALLOWED);
        assertThat(codeOf("https://evilpartner.io/")).isEqualTo(OutboundRequestPolicy.HOST_NOT_ALLOWED);
        assertThat(codeOf("http://169.254.169.254/latest/meta-data")).isEqualTo(OutboundRequestPolicy.HOST_NOT_ALLOWED);
    }

    @Test
    void emptyAllowlistDeniesEverything() {
        OutboundRequestPolicy none = policy(List.of(), true, List.of());
        assertThatThrownBy(() -> none.checkUrl(URI.create("https://api.example.com/")))
                .isInstanceOf(OutboundPolicyException.class);
    }

    @Test
    void rejectsOtherSchemesCredentialsAndRelativeUrls() {
        assertThat(codeOf("ftp://api.example.com/x")).isEqualTo(OutboundRequestPolicy.INVALID_URL);
        assertThat(codeOf("file:///etc/passwd")).isEqualTo(OutboundRequestPolicy.INVALID_URL);
        assertThat(codeOf("https://user:pw@api.example.com/")).isEqualTo(OutboundRequestPolicy.INVALID_URL);
        assertThat(codeOf("/relative")).isEqualTo(OutboundRequestPolicy.INVALID_URL);
        assertThat(codeOf("mailto:a@b.io")).isEqualTo(OutboundRequestPolicy.INVALID_URL);
    }

    @Test
    void portsCanBeRestricted() {
        OutboundRequestPolicy restricted = policy(List.of("api.example.com"), false, List.of(443));
        assertThatCode(() -> restricted.checkUrl(URI.create("https://api.example.com/"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> restricted.checkUrl(URI.create("https://api.example.com:8443/")))
                .isInstanceOfSatisfying(OutboundPolicyException.class,
                        e -> assertThat(e.code()).isEqualTo(OutboundRequestPolicy.PORT_NOT_ALLOWED));
        assertThatThrownBy(() -> restricted.checkUrl(URI.create("http://api.example.com/")))
                .isInstanceOf(OutboundPolicyException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.255.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.169.254", "100.64.0.1", "100.127.255.255", "0.0.0.0", "0.1.2.3", "224.0.0.1", "255.255.255.255",
            "240.0.0.1", "192.0.2.10", "198.18.0.1", "198.51.100.7", "203.0.113.9", "192.0.0.8",
            "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "64:ff9b::a9fe:a9fe",
            "2001:db8::1", "ff02::1"})
    void classifiesNonPublicAddresses(String address) throws Exception {
        assertThat(OutboundRequestPolicy.isNonPublic(InetAddress.getByName(address))).as(address).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2606:4700::1111",
            "::ffff:8.8.8.8"})
    void classifiesPublicAddresses(String address) throws Exception {
        assertThat(OutboundRequestPolicy.isNonPublic(InetAddress.getByName(address))).as(address).isFalse();
    }

    @Test
    void guardedResolverRefusesNonPublicAnswersEvenForAllowedHosts() throws Exception {
        DnsResolver fake = new FakeDns("10.0.0.5");
        GuardedDnsResolver guard = new GuardedDnsResolver(fake, policy);

        assertThatThrownBy(() -> guard.resolve("api.example.com"))
                .isInstanceOf(OutboundRequestPolicy.BlockedAddressException.class).hasMessageContaining("10.0.0.5");
    }

    @Test
    void guardedResolverRefusesMixedAnswers() {
        GuardedDnsResolver guard = new GuardedDnsResolver(new FakeDns("93.184.216.34", "127.0.0.1"), policy);
        assertThatThrownBy(() -> guard.resolve("api.example.com"))
                .isInstanceOf(OutboundRequestPolicy.BlockedAddressException.class);
    }

    @Test
    void guardedResolverRefusesHostsNotOnTheAllowlist() {
        GuardedDnsResolver guard = new GuardedDnsResolver(new FakeDns("93.184.216.34"), policy);
        assertThatThrownBy(() -> guard.resolve("other.example.com"))
                .isInstanceOf(OutboundRequestPolicy.BlockedAddressException.class);
    }

    @Test
    void dnsRebindingIsCaughtAtConnectTime() throws Exception {
        // First answer is public (what a pre-flight check would see), the next one points inside the network.
        FakeDns rebinding = new FakeDns("93.184.216.34") {
            private int calls;

            @Override
            public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                return calls++ == 0 ? super.resolve(host) : new InetAddress[] {InetAddress.getByName("169.254.169.254")};
            }
        };
        GuardedDnsResolver guard = new GuardedDnsResolver(rebinding, policy);

        assertThat(guard.resolve("api.example.com")[0].getHostAddress()).isEqualTo("93.184.216.34");
        assertThatThrownBy(() -> guard.resolve("api.example.com"))
                .isInstanceOf(OutboundRequestPolicy.BlockedAddressException.class).hasMessageContaining("169.254.169.254");
    }

    @Test
    void privateAddressesCanBeAllowedForDevelopment() throws Exception {
        OutboundRequestPolicy dev = policy(List.of("localhost"), true, List.of());
        GuardedDnsResolver guard = new GuardedDnsResolver(new FakeDns("127.0.0.1"), dev);
        assertThat(guard.resolve("localhost")).hasSize(1);
    }

    static class FakeDns implements DnsResolver {
        private final String[] answers;

        FakeDns(String... answers) {
            this.answers = answers;
        }

        @Override
        public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
            InetAddress[] out = new InetAddress[answers.length];
            for (int i = 0; i < answers.length; i++) {
                out[i] = InetAddress.getByName(answers[i]);
            }
            return out;
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }

    @Test
    void redactsSecretsInUrlsAndHeaders() {
        assertThat(Redaction.url("https://x.io/a?token=abc&page=2&api_key=k1&access_token=t#frag"))
                .isEqualTo("https://x.io/a?token=[REDACTED]&page=2&api_key=[REDACTED]&access_token=[REDACTED]#frag");
        assertThat(Redaction.header("Authorization", "Bearer abc")).isEqualTo(Redaction.REDACTED);
        assertThat(Redaction.header("X-Github-Token", "abc")).isEqualTo(Redaction.REDACTED);
        assertThat(Redaction.header("Content-Type", "application/json")).isEqualTo("application/json");
    }
}
