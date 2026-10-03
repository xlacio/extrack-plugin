package ru.extrack.plugin.util;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public final class Net {

    private Net() {
    }

    public static String address(InetSocketAddress socket) {
        return socket == null ? null : address(socket.getAddress());
    }

    /** Without the IPv6 scope suffix ("%eth0"): the API validates addresses strictly. */
    public static String address(InetAddress address) {
        if (address == null) return null;
        String host = address.getHostAddress();
        int scope = host.indexOf('%');
        return scope >= 0 ? host.substring(0, scope) : host;
    }
}
