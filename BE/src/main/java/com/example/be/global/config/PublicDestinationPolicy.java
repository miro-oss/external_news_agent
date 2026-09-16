package com.example.be.global.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/** The same public-network boundary applies to articles, redirects, resources and robots.txt. */
public final class PublicDestinationPolicy {

    private static final Set<String> INTERNAL_SUFFIXES = Set.of(
            "localhost", "local", "localdomain", "internal", "intranet", "lan", "home", "corp",
            "svc", "onion", "invalid", "test", "home.arpa");

    private PublicDestinationPolicy() {
    }

    /** Syntax/name/literal validation does not perform DNS; the transport validates resolved addresses. */
    public static void validate(URI uri) {
        if (uri == null || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme())) || uri.getRawUserInfo() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw denied();
        }
        validateHost(uri.getHost());
    }

    static String validateHost(String host) {
        if (host == null || host.isBlank()) {
            throw denied();
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.contains("%")) {
            throw denied();
        }
        if (normalized.contains(":")) {
            validateLiteral(normalized);
            return normalized;
        }
        if (normalized.matches("[0-9.]+")) {
            // Reject short, decimal and octal-looking IPv4 aliases before any resolver can reinterpret them.
            String[] octets = normalized.split("\\.", -1);
            if (octets.length != 4) {
                throw denied();
            }
            for (String octet : octets) {
                if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) {
                    throw denied();
                }
            }
            validateLiteral(normalized);
            return normalized;
        }
        if (!normalized.contains(".") || normalized.length() > 253) {
            throw denied();
        }
        for (String suffix : INTERNAL_SUFFIXES) {
            if (normalized.equals(suffix) || normalized.endsWith("." + suffix)) {
                throw denied();
            }
        }
        for (String label : normalized.split("\\.", -1)) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                throw denied();
            }
        }
        return normalized;
    }

    private static void validateLiteral(String value) {
        try {
            validateAddress(InetAddress.getByName(value));
        } catch (UnknownHostException exception) {
            throw denied();
        }
    }

    static void validateAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            throw denied();
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            if (first == 0 || first == 10 || first == 127 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    // Azure's public-looking WireServer IP addresses the host platform from a VM.
                    || (first == 168 && second == 63 && third == 129 && bytes[3] == 16)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && (second == 168 || (second == 0 && (third == 0 || third == 2))))
                    || (first == 192 && second == 88 && third == 99)
                    || (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                    || (first == 203 && second == 0 && third == 113)) {
                throw denied();
            }
        } else if (bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            // Only global unicast. Exclude protocol-assignment, documentation and IPv4 tunneling ranges.
            if ((first & 0xe0) != 0x20 || (first == 0x20 && second == 0x01 && third < 2)
                    || (first == 0x20 && second == 0x01 && third == 0x0d && bytes[3] == (byte) 0xb8)
                    || (first == 0x20 && second == 0x02)
                    || (first == 0x3f && second == 0xff && (third & 0xf0) == 0)) {
                throw denied();
            }
        } else {
            throw denied();
        }
    }

    private static RejectedDestinationException denied() {
        return new RejectedDestinationException();
    }

    /** A permanent policy denial can be distinguished from transient DNS or transport failures. */
    public static final class RejectedDestinationException extends IllegalArgumentException {
        private RejectedDestinationException() {
            super("Outbound destination is not a public HTTP(S) endpoint");
        }
    }
}
