package dev.wachipayox.bootoptim.optimization.client;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

/**
 * Immutable compact copy of the bake-time inputs of a quad list (exact {@link BakedQuad}s with 32 ints):
 * vertex words in 512-quad palette chunks with the UV words split off ({@link SplitQuadGeometry}),
 * packed direction/shade/ambient-occlusion flags, tint indices and sprites (a single value when all
 * quads agree). Everything is copied bit for bit.
 *
 * <p>{@link #readView()} returns a list whose elements are one ephemeral cursor of the view. That is
 * only safe because the audited consumer ({@code DecocraftRotatedQuadReuse.rotateQuads}) clones the
 * vertex array and copies the metadata before it asks for the next element, and the view is private
 * to one realization. The cursor is never exposed as model quads.</p>
 */
final class FrozenQuadInputs {
    private static final int STRIDE = 32;
    private static final int CHUNK = 512;
    private static final Direction[] DIRECTIONS = Direction.values();

    final int size;
    private final PalettedIntChunk[] vertices;
    private final PalettedIntChunk[] textureCoordinates;
    private final byte[] flags;
    private final int[] tints;
    private final int tint;
    private final TextureAtlasSprite[] sprites;
    private final TextureAtlasSprite sprite;

    FrozenQuadInputs(List<BakedQuad> source) {
        size = source.size();
        vertices = new PalettedIntChunk[(size + CHUNK - 1) / CHUNK];
        textureCoordinates = new PalettedIntChunk[vertices.length];
        flags = new byte[size];
        int[][] rawVertices = new int[vertices.length][];
        int[][] rawUv = new int[vertices.length][];
        for (int chunk = 0; chunk < vertices.length; chunk++) {
            int count = Math.min(CHUNK, size - chunk * CHUNK);
            rawVertices[chunk] = new int[count * 24];
            rawUv[chunk] = new int[count * 8];
        }
        int[] tintData = new int[size];
        TextureAtlasSprite[] spriteData = new TextureAtlasSprite[size];
        boolean sameTint = true;
        boolean sameSprite = true;
        for (int i = 0; i < size; i++) {
            BakedQuad quad = source.get(i);
            SplitQuadGeometry.pack(quad.getVertices(), rawVertices[i / CHUNK], (i % CHUNK) * 24, rawUv[i / CHUNK], (i % CHUNK) * 8);
            flags[i] = flag(quad);
            tintData[i] = quad.getTintIndex();
            spriteData[i] = quad.getSprite();
            if (i > 0) {
                sameTint &= tintData[i] == tintData[0];
                sameSprite &= spriteData[i] == spriteData[0];
            }
        }
        for (int chunk = 0; chunk < vertices.length; chunk++) {
            vertices[chunk] = SplitQuadGeometry.share(rawVertices[chunk]);
            textureCoordinates[chunk] = PalettedIntChunk.freeze(rawUv[chunk]);
        }
        tint = size == 0 ? -1 : tintData[0];
        sprite = size == 0 ? null : spriteData[0];
        tints = sameTint ? null : tintData;
        sprites = sameSprite ? null : spriteData;
    }

    private static byte flag(BakedQuad quad) {
        return (byte) (quad.getDirection().ordinal() | (quad.isShade() ? 8 : 0) | (quad.hasAmbientOcclusion() ? 16 : 0));
    }

    /** Diagnostic: the decoded view equals the source quads, field by field. */
    boolean verifyReadView(List<BakedQuad> current) {
        List<BakedQuad> view = readView();
        for (int i = 0; i < size; i++) {
            BakedQuad expected = current.get(i);
            BakedQuad actual = view.get(i);
            if (!Arrays.equals(expected.getVertices(), actual.getVertices())
                    || expected.getTintIndex() != actual.getTintIndex()
                    || expected.getDirection() != actual.getDirection()
                    || expected.getSprite() != actual.getSprite()
                    || expected.isShade() != actual.isShade()
                    || expected.hasAmbientOcclusion() != actual.hasAmbientOcclusion()) {
                return false;
            }
        }
        return true;
    }

    /** True when {@code current} still equals what was frozen (identical inputs, nothing mutated since). */
    boolean matches(List<BakedQuad> current) {
        if (current.size() != size) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            BakedQuad quad = current.get(i);
            if (flag(quad) != flags[i] || quad.getTintIndex() != tint(i) || quad.getSprite() != sprite(i)
                    || !SplitQuadGeometry.matches(quad.getVertices(), vertices[i / CHUNK], (i % CHUNK) * 24,
                            textureCoordinates[i / CHUNK], (i % CHUNK) * 8)) {
                return false;
            }
        }
        return true;
    }

    long vertexPayload() {
        return (long) size * STRIDE * 4;
    }

    private int tint(int i) {
        return tints == null ? tint : tints[i];
    }

    private TextureAtlasSprite sprite(int i) {
        return sprites == null ? sprite : sprites[i];
    }

    List<BakedQuad> readView() {
        return new ReadView();
    }

    private final class ReadView extends AbstractList<BakedQuad> implements RandomAccess {
        private final Cursor cursor = new Cursor();

        @Override public int size() { return size; }

        @Override public BakedQuad get(int index) {
            cursor.index = Objects.checkIndex(index, size);
            return cursor;
        }
    }

    /** Safe only because the guarded rotation loop clones vertices and copies metadata before its next input. */
    private final class Cursor extends BakedQuad {
        int index;
        final int[] scratch = new int[STRIDE];

        Cursor() {
            super(new int[STRIDE], -1, Direction.UP, FrozenQuadInputs.this.sprite, false, false);
        }

        @Override public int[] getVertices() {
            SplitQuadGeometry.unpack(FrozenQuadInputs.this.vertices[index / CHUNK], (index % CHUNK) * 24,
                    textureCoordinates[index / CHUNK], (index % CHUNK) * 8, scratch);
            return scratch;
        }

        @Override public int getTintIndex() { return tint(index); }

        @Override public boolean isTinted() { return getTintIndex() != -1; }

        @Override public Direction getDirection() { return DIRECTIONS[flags[index] & 7]; }

        @Override public TextureAtlasSprite getSprite() { return sprite(index); }

        @Override public boolean isShade() { return (flags[index] & 8) != 0; }

        @Override public boolean hasAmbientOcclusion() { return (flags[index] & 16) != 0; }
    }
}
