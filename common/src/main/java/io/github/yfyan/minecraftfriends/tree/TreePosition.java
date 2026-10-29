package io.github.yfyan.minecraftfriends.tree;

/** Minecraft-independent, bounded block position. Coordinates are never silently clamped. */
public record TreePosition(int x, int y, int z) {
    public static final int MAX_HORIZONTAL = 30_000_000;
    public static final int MIN_HEIGHT = -2048;
    public static final int MAX_HEIGHT = 2048;

    public TreePosition {
        if (x < -MAX_HORIZONTAL || x > MAX_HORIZONTAL || z < -MAX_HORIZONTAL || z > MAX_HORIZONTAL
                || y < MIN_HEIGHT || y > MAX_HEIGHT) {
            throw new IllegalArgumentException("Tree position exceeds supported world bounds.");
        }
    }

    public TreePosition offset(int dx, int dy, int dz) {
        return new TreePosition(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }
}
