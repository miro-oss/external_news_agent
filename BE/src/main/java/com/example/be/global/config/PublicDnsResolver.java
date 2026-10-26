package com.example.be.global.config;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Supplies validated addresses directly to HttpClient's connection operator, without a second DNS lookup. */
final class PublicDnsResolver implements DnsResolver {

    private final DnsResolver delegate;

    PublicDnsResolver(DnsResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        try {
            String normalized = PublicDestinationPolicy.validateHost(host);
            InetAddress[] addresses = delegate.resolve(normalized);
            if (addresses == null || addresses.length == 0) {
                throw new UnknownHostException("Public destination has no resolved addresses");
            }
            // Reject the whole answer when even one address is private; never silently select a safe subset.
            for (InetAddress address : addresses) {
                PublicDestinationPolicy.validateAddress(address);
            }
            return addresses.clone();
        } catch (IllegalArgumentException exception) {
            UnknownHostException denied = new UnknownHostException("Outbound destination is not public");
            denied.initCause(exception);
            throw denied;
        }
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        try {
            // TLS/Host must retain the requested name; a reverse DNS name is not its identity.
            return PublicDestinationPolicy.validateHost(host);
        } catch (IllegalArgumentException exception) {
            UnknownHostException denied = new UnknownHostException("Outbound destination is not public");
            denied.initCause(exception);
            throw denied;
        }
    }
}
