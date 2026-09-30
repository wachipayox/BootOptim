package dev.wachipayox.bootoptim.optimization.client;

/** Optional bridge to Sodium's existing getters; no Sodium runtime dependency. */
public interface QuadCoordinateView {
    float getX(int vertex);
    float getY(int vertex);
    float getZ(int vertex);
}
