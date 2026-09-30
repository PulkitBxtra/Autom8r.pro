package com.bxtralabs.pod.processor.service.handlers;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;

// Keeps HTTP steps to the public internet. A workflow author chooses the URL, so without this a
// step could call the pods' internal APIs (localhost:8084/internal/...), the cloud metadata
// service (169.254.169.254) or anything else on our network, and show the answer in the run.
//
// Every address the host resolves to must be public; one internal address is enough to refuse,
// since the client may connect to any of them. Redirects are checked hop by hop by the caller.
//
// Not covered: a DNS answer that changes between this check and the connection (DNS rebinding).
// Closing that needs connecting to the checked address itself, which java.net.http can't do.
public final class UrlGuard {

    // How host names are resolved; replaced in tests.
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public static final Resolver DNS = InetAddress::getAllByName;

    private final Resolver resolver;
    private final boolean allowPrivate;

    public UrlGuard(Resolver resolver, boolean allowPrivate) {
        this.resolver = resolver;
        this.allowPrivate = allowPrivate;
    }

    // Throws PermanentStepException for a URL an HTTP step may not call. A host that doesn't
    // resolve is left to the request itself (a DNS failure may pass, so it's retried).
    public void check(URI uri) throws PermanentStepException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new PermanentStepException("HTTP steps can only call http:// and https:// URLs, not " + uri);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new PermanentStepException("The URL " + uri + " has no host");
        }
        if (allowPrivate) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
        } catch (UnknownHostException e) {
            return;
        }
        for (InetAddress a : addresses) {
            if (isInternal(a)) {
                throw new PermanentStepException("HTTP steps can't call " + host
                        + (host.equals(a.getHostAddress()) ? "" : " (" + a.getHostAddress() + ")")
                        + ": it's a private or internal address. Only public addresses can be called.");
            }
        }
    }

    // Loopback, private, link-local (incl. cloud metadata), carrier-grade NAT, multicast,
    // reserved and documentation ranges, and IPv6 forms that carry one of those IPv4 addresses.
    static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            return internalV4(b);
        }
        if (a instanceof Inet6Address v6) {
            // fc00::/7 unique local
            if ((b[0] & 0xfe) == 0xfc) return true;
            // ::a.b.c.d (IPv4-compatible) and ::ffff:a.b.c.d (IPv4-mapped; Java usually turns
            // these into Inet4Address already)
            if (v6.isIPv4CompatibleAddress() || isMapped(b)) {
                return internalV4(Arrays.copyOfRange(b, 12, 16));
            }
            // 64:ff9b::/96 NAT64 carries an IPv4 address in its last 4 bytes
            if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b && allZero(b, 4, 12)) {
                return internalV4(Arrays.copyOfRange(b, 12, 16));
            }
            // 2002::/16 6to4 carries one in bytes 2..5
            if (b[0] == 0x20 && b[1] == 0x02) {
                return internalV4(Arrays.copyOfRange(b, 2, 6));
            }
            // 2001:db8::/32 documentation
            return b[0] == 0x20 && b[1] == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8;
        }
        return true; // an address type we don't know: don't call it
    }

    private static boolean internalV4(byte[] b) {
        int o1 = b[0] & 0xff, o2 = b[1] & 0xff, o3 = b[2] & 0xff;
        return o1 == 0                                   // 0.0.0.0/8 "this network"
                || o1 == 10                              // 10/8 private
                || o1 == 127                             // loopback
                || (o1 == 100 && o2 >= 64 && o2 <= 127)  // 100.64/10 carrier-grade NAT
                || (o1 == 169 && o2 == 254)              // link-local, cloud metadata
                || (o1 == 172 && o2 >= 16 && o2 <= 31)   // 172.16/12 private
                || (o1 == 192 && o2 == 0 && o3 == 0)     // 192.0.0/24 IETF protocol assignments
                || (o1 == 192 && o2 == 0 && o3 == 2)     // TEST-NET-1
                || (o1 == 192 && o2 == 168)              // 192.168/16 private
                || (o1 == 198 && (o2 == 18 || o2 == 19)) // 198.18/15 benchmarking
                || (o1 == 198 && o2 == 51 && o3 == 100)  // TEST-NET-2
                || (o1 == 203 && o2 == 0 && o3 == 113)   // TEST-NET-3
                || o1 >= 224;                            // multicast, reserved, broadcast
    }

    private static boolean isMapped(byte[] b) {
        return allZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }
}
