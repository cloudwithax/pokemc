package dev.clxud.poke.mcp;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Operator-configured hardening for the MCP server. Because the transport is
 * plain HTTP, the bearer key alone can leak; this policy shrinks the blast
 * radius two ways: a source-IP allowlist (network perimeter) and a tool gate
 * (what a caller may do even with a valid key). Both default to "open" so an
 * unconfigured server behaves exactly as before.
 */
public final class SecurityPolicy {

    /** Tools that mutate the server or persistent state — blocked in read-only mode. */
    private static final Set<String> WRITE_TOOLS =
            Set.of("run_command", "assign_quest", "complete_quest", "remember");
    /** Operational tools always permitted (handshake + the genie's only voice). */
    private static final Set<String> ALWAYS_TOOLS =
            Set.of("confirm_tools", "reply_to_player");

    // Thread-safe: IPs can be appended at runtime (learned callers, async public-IP lookup)
    // while request threads read it.
    private final CopyOnWriteArrayList<Cidr> ranges = new CopyOnWriteArrayList<>();
    private final boolean restrict;    // whether the IP allowlist is enforced at all
    private final boolean readOnly;
    private final Set<String> allowedTools; // empty = every tool

    /**
     * @param allowedIps     operator-configured entries
     * @param autoAllowedIps entries added automatically (own public IP, loopback) — matched, but
     *                       they never on their own switch enforcement on, so they can't lock out Poke
     * @param enforce        whether to apply the IP allowlist at all
     */
    public SecurityPolicy(List<String> allowedIps, List<String> autoAllowedIps, boolean enforce,
                          boolean readOnly, List<String> allowedTools, Logger logger) {
        this.restrict = enforce;
        ranges.addAll(parseCidrs(allowedIps, logger));
        ranges.addAll(parseCidrs(autoAllowedIps, logger));
        this.readOnly = readOnly;
        List<String> tools = new ArrayList<>();
        if (allowedTools != null) {
            for (String t : allowedTools) {
                if (t != null && !t.isBlank()) {
                    tools.add(t.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.allowedTools = Set.copyOf(tools);
    }

    /** True if {@code addr} may reach the server. No operator allowlist = allow all. */
    boolean ipAllowed(InetAddress addr) {
        if (!restrict) {
            return true;
        }
        if (addr == null) {
            return false;
        }
        for (Cidr c : ranges) {
            if (c.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds {@code addr} as an exact-host allowance at runtime. Returns true only if it
     * was newly added (so the caller knows whether to persist it). No-op when the
     * allowlist isn't enforced or the address is already covered.
     */
    public synchronized boolean addAllowed(InetAddress addr) {
        if (addr == null || !restrict || ipAllowed(addr)) {
            return false;
        }
        byte[] raw = addr.getAddress();
        ranges.add(new Cidr(raw, raw.length * 8));
        return true;
    }

    /** True if a caller may invoke {@code tool}, honouring read-only and the allowlist. */
    boolean toolAllowed(String tool) {
        if (tool == null) {
            return true; // let dispatch report it as unknown
        }
        String name = tool.toLowerCase(Locale.ROOT);
        if (ALWAYS_TOOLS.contains(name)) {
            return true;
        }
        if (readOnly && WRITE_TOOLS.contains(name)) {
            return false;
        }
        return allowedTools.isEmpty() || allowedTools.contains(name);
    }

    /** One-line description of what is active, for the startup log. */
    public String summary() {
        String ips = restrict ? (ranges.size() + " IP rule(s)") : "any IP";
        String tools = allowedTools.isEmpty() ? "all tools" : (allowedTools.size() + "-tool allowlist");
        return ips + ", read-only=" + readOnly + ", " + tools;
    }

    // ---- CIDR / IP matching ------------------------------------------------

    private static List<Cidr> parseCidrs(List<String> rules, Logger logger) {
        List<Cidr> out = new ArrayList<>();
        if (rules == null) {
            return out;
        }
        for (String rule : rules) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            Cidr c = Cidr.parse(rule.trim());
            if (c == null) {
                logger.warning("Ignoring invalid mcp.allowed-ips entry: '" + rule + "'");
            } else {
                out.add(c);
            }
        }
        return out;
    }

    /** A network range: an exact IP (prefix = full length) or a CIDR block. */
    private static final class Cidr {
        private final byte[] network;
        private final int prefixBits;

        private Cidr(byte[] network, int prefixBits) {
            this.network = network;
            this.prefixBits = prefixBits;
        }

        static Cidr parse(String rule) {
            try {
                int slash = rule.indexOf('/');
                String host = slash < 0 ? rule : rule.substring(0, slash);
                // Literal IPs only — reject hostnames so we never trigger a DNS lookup.
                if (!host.contains(":") && !host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                    return null;
                }
                byte[] net = InetAddress.getByName(host).getAddress();
                int bits = slash < 0 ? net.length * 8 : Integer.parseInt(rule.substring(slash + 1).trim());
                if (bits < 0 || bits > net.length * 8) {
                    return null;
                }
                return new Cidr(net, bits);
            } catch (Exception e) {
                return null;
            }
        }

        boolean contains(InetAddress addr) {
            byte[] a = addr.getAddress();
            if (a.length != network.length) {
                return false; // never match v4 against v6
            }
            int fullBytes = prefixBits / 8;
            int remBits = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (a[i] != network[i]) {
                    return false;
                }
            }
            if (remBits > 0) {
                int mask = (0xFF << (8 - remBits)) & 0xFF;
                return (a[fullBytes] & mask) == (network[fullBytes] & mask);
            }
            return true;
        }
    }
}
