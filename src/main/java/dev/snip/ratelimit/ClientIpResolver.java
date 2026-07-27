package dev.snip.ratelimit;

import dev.snip.config.SnipProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out who the client actually is, behind a reverse proxy.
 *
 * <p><b>The trap.</b> Behind Nginx, {@code getRemoteAddr()} returns Nginx's own address,
 * so every user in the world shares one rate-limit bucket and the entire user base gets
 * throttled as a single client. The fix is to read {@code X-Forwarded-For}, where the
 * original client is the <em>first</em> entry (each proxy appends).
 *
 * <p><b>The caveat that has to come with it.</b> Any client can simply send an
 * {@code X-Forwarded-For} header of its choosing, so trusting it unconditionally turns
 * the rate limiter into a no-op: the attacker just rotates a fake value per request.
 * So the header is honoured only when the immediate peer is one of the configured proxy
 * ranges. From anywhere else, the socket address wins.
 */
@Slf4j
@Component
public class ClientIpResolver {

    private record Cidr(byte[] network, int prefixBits) {
        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xff << (8 - remainingBits)) & 0xff;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Cidr> trustedProxies;

    public ClientIpResolver(SnipProperties props) {
        this.trustedProxies = new ArrayList<>();
        for (String cidr : props.getRateLimit().getTrustedProxies()) {
            parse(cidr).ifPresent(trustedProxies::add);
        }
        log.info("Trusting X-Forwarded-For from {} proxy range(s)", trustedProxies.size());
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer == null) {
            return "unknown";
        }
        if (!isTrustedPeer(peer)) {
            return peer;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp.trim() : peer;
    }

    private boolean isTrustedPeer(String peer) {
        if (trustedProxies.isEmpty()) {
            return false;
        }
        byte[] addr;
        try {
            addr = InetAddress.getByName(peer).getAddress();
        } catch (UnknownHostException e) {
            return false;
        }
        return trustedProxies.stream().anyMatch(c -> c.contains(addr));
    }

    private static java.util.Optional<Cidr> parse(String cidr) {
        try {
            String[] parts = cidr.split("/");
            InetAddress base = InetAddress.getByName(parts[0]);
            int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : base.getAddress().length * 8;
            return java.util.Optional.of(new Cidr(base.getAddress(), bits));
        } catch (UnknownHostException | NumberFormatException e) {
            log.warn("Ignoring unparseable trusted-proxy CIDR '{}'", cidr);
            return java.util.Optional.empty();
        }
    }
}
