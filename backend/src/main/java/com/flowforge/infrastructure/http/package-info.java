/**
 * Outbound HTTP for workflow nodes, with SSRF protection: hostname allowlist, blocked non-public addresses, DNS
 * pinning (the addresses that pass the check are exactly the ones connected to), re-checked redirects, size and time
 * limits, and secret redaction.
 */
package com.flowforge.infrastructure.http;
