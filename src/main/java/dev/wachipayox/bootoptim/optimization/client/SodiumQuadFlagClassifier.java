package dev.wachipayox.bootoptim.optimization.client;

/** Pure alternative to the pinned Sodium 0.8.12-beta.1 quad classifier. */
public final class SodiumQuadFlagClassifier {
    private SodiumQuadFlagClassifier() {}

    public static int classify(QuadCoordinateView view, int direction) {
        // Keep all twelve virtual reads in stock vertex / X,Y,Z order.
        float x0 = view.getX(0), y0 = view.getY(0), z0 = view.getZ(0);
        float x1 = view.getX(1), y1 = view.getY(1), z1 = view.getZ(1);
        float x2 = view.getX(2), y2 = view.getY(2), z2 = view.getZ(2);
        float x3 = view.getX(3), y3 = view.getY(3), z3 = view.getZ(3);
        return switch (direction) {
            case 0, 1 -> flags(y0, y1, y2, y3, x0, x1, x2, x3, z0, z1, z2, z3, direction == 1);
            case 2, 3 -> flags(z0, z1, z2, z3, x0, x1, x2, x3, y0, y1, y2, y3, direction == 3);
            case 4, 5 -> flags(x0, x1, x2, x3, y0, y1, y2, y3, z0, z1, z2, z3, direction == 5);
            default -> throw new IllegalArgumentException("Unexpected direction ordinal: " + direction);
        };
    }

    private static int flags(float p0, float p1, float p2, float p3,
                             float a0, float a1, float a2, float a3,
                             float b0, float b1, float b2, float b3, boolean positive) {
        // Direct stock comparisons matter for NaN: negating a "full" test
        // would change its classification.
        boolean partial = min(a0, a1, a2, a3) >= 1.0e-4F
                || min(b0, b1, b2, b3) >= 1.0e-4F
                || max(a0, a1, a2, a3) <= 0.9999F
                || max(b0, b1, b2, b3) <= 0.9999F;
        // Sodium starts axis bounds at +32/-32. Constant planes outside this
        // interval are therefore NOT parallel. NaN also remains nonparallel.
        boolean parallel = p0 >= -32.0F && p0 <= 32.0F && p0 == p1 && p0 == p2 && p0 == p3;
        boolean aligned = parallel && (positive ? p0 > 0.9999F : p0 < 1.0e-4F);
        return (partial ? 1 : 0) | (parallel ? 2 : 0) | (aligned ? 4 : 0);
    }

    private static float min(float a, float b, float c, float d) {
        return Math.min(Math.min(Math.min(Math.min(32.0F, a), b), c), d);
    }

    private static float max(float a, float b, float c, float d) {
        return Math.max(Math.max(Math.max(Math.max(-32.0F, a), b), c), d);
    }
}
