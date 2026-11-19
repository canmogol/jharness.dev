package dev.jharness.net;

import java.net.InetAddress;

/** An address range: a base address and a prefix length. IPv4 and IPv6 never match each other. */
public record Range(byte[] base, int bits) {

    public static Range of(InetAddress base, int bits) {
        int max = base.getAddress().length * 8;
        if (bits < 0 || bits > max) {
            throw new IllegalArgumentException("prefix /" + bits + " out of range (0 to " + max + ")");
        }
        return new Range(base.getAddress(), bits);
    }

    /** {@code 10.0.0.0/8}, {@code ::1}, {@code fc00::/7}; the address must be a literal. */
    public static Range parse(String text) {
        int slash = text.indexOf('/');
        String addr = slash < 0 ? text : text.substring(0, slash);
        InetAddress a = InetAddress.ofLiteral(addr);
        int bits = a.getAddress().length * 8;
        if (slash >= 0) {
            String b = text.substring(slash + 1);
            if (!b.matches("[0-9]{1,3}")) {
                throw new IllegalArgumentException("bad prefix length " + b);
            }
            bits = Integer.parseInt(b);
        }
        return of(a, bits);
    }

    public boolean contains(InetAddress ip) {
        if (ip == null) {
            return false;
        }
        byte[] a = ip.getAddress();
        if (a.length != base.length) {
            return false;
        }
        int full = bits / 8;
        for (int i = 0; i < full; i++) {
            if (a[i] != base[i]) {
                return false;
            }
        }
        int rest = bits % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xff << (8 - rest)) & 0xff;
        return (a[full] & mask) == (base[full] & mask);
    }
}
