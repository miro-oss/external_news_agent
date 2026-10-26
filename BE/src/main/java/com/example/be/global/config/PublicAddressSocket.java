package com.example.be.global.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;

/** Enforces the policy at connect(), including routes that already contain an IP address. */
final class PublicAddressSocket extends Socket {

    PublicAddressSocket() {
        super(Proxy.NO_PROXY);
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        if (!(endpoint instanceof InetSocketAddress resolved) || resolved.isUnresolved()) {
            throw new IOException("Public outbound socket requires a validated resolved address");
        }
        try {
            PublicDestinationPolicy.validateAddress(resolved.getAddress());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Outbound socket destination is not public", exception);
        }
        super.connect(endpoint, timeout);
    }
}
