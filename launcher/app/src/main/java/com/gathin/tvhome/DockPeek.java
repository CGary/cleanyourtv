package com.gathin.tvhome;

/**
 * Dock peek (config.xml, dock_peek): while focus is in the top bar the dock sinks towards the bottom edge, so the
 * wallpaper or sky above it shows unobstructed; it springs back up as soon as a tile takes focus. "ultra" leaves
 * a sliver of the band on screen, "balanced" about half of it, "none" keeps the dock where it is.
 */
final class DockPeek {
    static final String NONE = "none", BALANCED = "balanced", ULTRA = "ultra";
    /** What "ultra" leaves of the band above the bottom edge (dp): enough to see that the dock is there. */
    static final float ULTRA_VISIBLE_DP = 30f;

    private DockPeek() { }

    /** A known mode, or none for anything else (a typo in a theme must not move the dock). */
    static String mode(String value) {
        if (value == null) return NONE;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return BALANCED.equals(v) || ULTRA.equals(v) ? v : NONE;
    }

    /**
     * How far down to move the grid (px) so the given share of the dock band stays above {@code bottom}.
     *
     * @param bottom     the screen's bottom edge, in the same coordinates as {@code dockTop}
     * @param dockTop    the top of the dock band with the grid unmoved
     * @param dockHeight the band's height
     */
    static float offset(String mode, int bottom, int dockTop, int dockHeight, float density) {
        if (NONE.equals(mode) || dockHeight <= 0) return 0f;
        int visible = ULTRA.equals(mode) ? Math.round(ULTRA_VISIBLE_DP * density) : dockHeight / 2;
        return Math.max(0f, bottom - visible - dockTop);
    }

    /** The next mode in the order the settings row offers them. */
    static String next(String mode) {
        switch (mode(mode)) {
            case NONE: return BALANCED;
            case BALANCED: return ULTRA;
            default: return NONE;
        }
    }
}
