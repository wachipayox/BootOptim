package com.razz.decocraft.models.bbmodel;

/** Contract-test stand-in: only the nested vertex type with the audited five float fields. Never packaged. */
public class BlockbenchBakery {
    public static class VertexData {
        public float x;
        public float y;
        public float z;
        public float u;
        public float v;
    }

    /** Same name pattern, one extra non-float field: the shape check must reject it. */
    public static class OddVertexData {
        public float x;
        public String label;
    }
}
