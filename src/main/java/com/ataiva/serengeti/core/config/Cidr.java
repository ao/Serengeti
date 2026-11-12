package com.ataiva.serengeti.core.config;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * An address range for opt-in discovery. At most 256 addresses: a /24 for IPv4, a /120 for IPv6. Only
 * numeric addresses are accepted, so parsing never does a DNS lookup.
 */
public record Cidr(InetAddress network, int prefix) {
    public static final int MAX_ADDRESSES = 256;

    public static Cidr parse(String s) {
        String t = s.trim();
        int slash = t.indexOf('/');
        if (slash <= 0 || slash == t.length() - 1) {
            throw new ConfigException("discovery_cidr '" + t + "' must look like 10.0.0.0/24");
        }
        String addr = t.substring(0, slash);
        if (!addr.matches("[0-9.]+|[0-9A-Fa-f:]+")) {
            throw new ConfigException("discovery_cidr '" + t + "' must use a numeric address");
        }
        InetAddress a;
        try {
            a = InetAddress.getByName(addr);
        } catch (UnknownHostException e) {
            throw new ConfigException("discovery_cidr '" + t + "' has an invalid address");
        }
        int bits = a.getAddress().length * 8;
        int prefix;
        try {
            prefix = Integer.parseInt(t.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new ConfigException("discovery_cidr '" + t + "' has an invalid prefix length");
        }
        if (prefix < 0 || prefix > bits) {
            throw new ConfigException("discovery_cidr '" + t + "' has an invalid prefix length");
        }
        if (bits - prefix > 8) {
            throw new ConfigException("discovery_cidr '" + t + "' is too wide: at most " + MAX_ADDRESSES
                    + " addresses (/" + (bits - 8) + ")");
        }
        BigInteger mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
                .xor(BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE));
        BigInteger value = new BigInteger(1, a.getAddress());
        if (!value.and(mask).equals(value)) {
            throw new ConfigException("discovery_cidr '" + t + "' has host bits set");
        }
        return new Cidr(a, prefix);
    }

    /** Every address in the range, network and broadcast included (peers may use either on IPv6). */
    public List<InetAddress> addresses() {
        byte[] base = network.getAddress();
        int count = 1 << (base.length * 8 - prefix);
        List<InetAddress> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] b = base.clone();
            b[b.length - 1] = (byte) ((b[b.length - 1] & 0xff) + i);
            try {
                out.add(InetAddress.getByAddress(b));
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return network.getHostAddress() + "/" + prefix;
    }
}
