package com.fincore.gateway.security;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * Works out the real client address. {@code X-Forwarded-For} is trivially forged, so it is only
 * believed when the TCP peer is one of our own trusted proxies (the web server, ingress, load
 * balancer). Walking the header from the right and skipping trusted proxies yields the first
 * untrusted hop, which is the client as seen by our own edge: an attacker cannot choose it by
 * sending a fake header, so they cannot dodge per-IP rate limits.
 */
@Component
public class ClientIpResolver {

    private record Cidr(byte[] network, int prefix) {
        boolean contains(InetAddress address) {
            byte[] a = address.getAddress();
            if (a.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (a[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (a[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Cidr> trusted = new ArrayList<>();

    public ClientIpResolver(
            @Value(
                            "${fincore.security.trusted-proxies:127.0.0.1/32,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}")
                    List<String> trustedProxies) {
        for (String entry : trustedProxies) {
            if (!entry.isBlank()) {
                trusted.add(parseCidr(entry.trim()));
            }
        }
    }

    public String resolve(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        InetAddress peer = remote.getAddress();
        String peerText = peer.getHostAddress();
        if (!isTrusted(peer)) {
            return peerText; // direct client: ignore any forwarding header it sent
        }
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || forwarded.length() > 512) {
            return peerText;
        }
        String[] hops = forwarded.split(",");
        String leftmost = peerText;
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            InetAddress address = literal(hop);
            if (address == null) {
                return peerText; // garbage in the header: do not guess
            }
            leftmost = address.getHostAddress();
            if (!isTrusted(address)) {
                return leftmost;
            }
        }
        return leftmost;
    }

    boolean isTrusted(InetAddress address) {
        return trusted.stream().anyMatch(c -> c.contains(address));
    }

    private static Cidr parseCidr(String text) {
        String[] parts = text.split("/");
        InetAddress address = literal(parts[0]);
        if (address == null) {
            throw new IllegalArgumentException("Invalid trusted proxy address: " + text);
        }
        int bits = address.getAddress().length * 8;
        int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : bits;
        if (prefix < 0 || prefix > bits) {
            throw new IllegalArgumentException("Invalid prefix length in trusted proxy: " + text);
        }
        return new Cidr(address.getAddress(), prefix);
    }

    /**
     * Parses an IP literal only. Anything else returns null, so we can never trigger a DNS lookup
     * from a header.
     */
    private static InetAddress literal(String text) {
        if (text.isEmpty() || text.length() > 45 || !text.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
