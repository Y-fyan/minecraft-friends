package io.github.yfyan.minecraftfriends.action;

/** Inclusive, explicitly owner-selected cuboid used to bound destructive actions. */
public record BlockArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public static final int MAX_VOLUME = 4096;

    public BlockArea {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Area corners must be ordered.");
        }
        long volume = volumeOf(minX, minY, minZ, maxX, maxY, maxZ);
        if (volume > MAX_VOLUME) {
            throw new IllegalArgumentException("Area exceeds the 4096 block limit.");
        }
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public long volume() {
        return volumeOf(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static long volumeOf(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        long xLength = (long) maxX - minX + 1;
        long yLength = (long) maxY - minY + 1;
        long zLength = (long) maxZ - minZ + 1;
        if (xLength > MAX_VOLUME || yLength > MAX_VOLUME || zLength > MAX_VOLUME) {
            return (long) MAX_VOLUME + 1;
        }
        return xLength * yLength * zLength;
    }
}
