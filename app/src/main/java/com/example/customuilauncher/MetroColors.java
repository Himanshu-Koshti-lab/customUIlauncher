package com.example.customuilauncher;

/** Windows 8 accent colors; each app gets a stable one based on its package name. */
final class MetroColors {
    private static final int[] PALETTE = {
            0xFF2D89EF, // blue
            0xFF00ABA9, // teal
            0xFF8CBF26, // lime
            0xFFA200FF, // purple
            0xFFFF0097, // magenta
            0xFFF09609, // orange
            0xFFE51400, // red
            0xFF339933, // green
            0xFF603CBA, // dark purple
            0xFFDA532C, // dark orange
            0xFF1BA1E2, // cyan
            0xFFA05000, // brown
    };

    private MetroColors() {}

    static int forKey(String key) {
        return PALETTE[Math.floorMod(key.hashCode(), PALETTE.length)];
    }
}
