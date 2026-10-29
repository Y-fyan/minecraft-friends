package io.github.yfyan.minecraftfriends.tree;

/** Only these ordinary tree materials are supported; tags do not grant destruction authority. */
public enum TreeSpecies {
    OAK("minecraft:oak_log", "minecraft:oak_leaves", "minecraft:oak_sapling"),
    BIRCH("minecraft:birch_log", "minecraft:birch_leaves", "minecraft:birch_sapling");

    private final String logId;
    private final String leavesId;
    private final String saplingId;

    TreeSpecies(String logId, String leavesId, String saplingId) {
        this.logId = logId;
        this.leavesId = leavesId;
        this.saplingId = saplingId;
    }

    public String logId() {
        return logId;
    }

    public String leavesId() {
        return leavesId;
    }

    public String saplingId() {
        return saplingId;
    }
}
