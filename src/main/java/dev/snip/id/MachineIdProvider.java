package dev.snip.id;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves this instance's Snowflake machine id (0..1023).
 *
 * <p>Order of preference:
 * <ol>
 *   <li>Explicit {@code app.machine-id} / {@code APP_MACHINE_ID}. This is what the
 *       Compose file sets — one distinct value per app service.</li>
 *   <li>Hash of the container hostname, modulo 1024.</li>
 * </ol>
 *
 * <p>The fallback exists because of a trap worth knowing about: {@code docker compose
 * up --scale app=3} hands every replica the <em>same</em> environment, so all three
 * would run with {@code APP_MACHINE_ID=0} and could mint colliding ids. Docker gives
 * each container a distinct hostname, so hashing it recovers (probabilistic) uniqueness.
 *
 * <p>It is only probabilistic — 1024 buckets means collisions are possible by the
 * birthday bound at a few dozen nodes — so it is a safety net, not the design. This
 * project uses explicitly numbered services (app1/app2/app3) as the real answer. In
 * Kubernetes you would take the StatefulSet pod ordinal; at larger scale, an ephemeral
 * lease from ZooKeeper/etcd handed out at startup.
 */
@Slf4j
@Component
public class MachineIdProvider {

    private final long machineId;

    public MachineIdProvider(@Value("${app.machine-id:-1}") long configured) {
        if (configured >= 0) {
            if (configured > SnowflakeIdGenerator.MAX_MACHINE_ID) {
                throw new IllegalArgumentException(
                        "app.machine-id must be 0.." + SnowflakeIdGenerator.MAX_MACHINE_ID + ", got " + configured);
            }
            this.machineId = configured;
            log.info("Machine id {} taken from configuration", machineId);
        } else {
            this.machineId = deriveFromHostname();
            log.warn("app.machine-id is not set; derived machine id {} from hostname. "
                    + "Set APP_MACHINE_ID explicitly per instance in production.", machineId);
        }
    }

    public long machineId() {
        return machineId;
    }

    private static long deriveFromHostname() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = String.valueOf(ProcessHandle.current().pid());
            log.warn("Hostname unavailable, falling back to pid {}", host);
        }
        // Math.abs(Integer.MIN_VALUE) is still negative, so mask the sign bit instead.
        return (host.hashCode() & 0x7fffffffL) % (SnowflakeIdGenerator.MAX_MACHINE_ID + 1);
    }
}
