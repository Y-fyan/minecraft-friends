package io.github.yfyan.minecraftfriends.action;

/**
 * Fail-closed accounting for a tree executor that mines and collects one log at a time.
 * The caller must bind {@code expected} to the exact item entity UUID observed after its own
 * successful break, and consume that ledger entry after crediting a real vanilla pickup.
 * Merged or otherwise ambiguous stacks are deliberately not credited as task harvest.
 */
public final class TreeDropAccounting {
    private static final int MAX_PLANNED_LOGS = 8;

    private TreeDropAccounting() {
    }

    /**
     * Identity witness for the originally observed mutable stack, not an equality comparison.
     * Minecraft 1.21.1 ItemEntity.merge installs a copy on the receiving entity; checking only
     * UUID/count would accept a mixed stack after another player's partial pickup.
     * The witness lives only for this outstanding drop and is never persisted across reloads.
     */
    public static boolean sameSource(Object originalStack, Object currentStack) {
        return originalStack != null && originalStack == currentStack;
    }

    /** Only a new, single-log entity can establish provenance for one completed break. */
    public static boolean isNewSingleLog(int baselineCount, int afterCount) {
        return baselineCount == 0 && afterCount == 1;
    }

    /**
     * Returns credit for an actual pickup callback, or zero when source/count evidence is ambiguous.
     * Exactly one expected log is required: accepting larger stacks would silently accept merged
     * unrelated items, since this executor never has more than one outstanding mined log.
     */
    public static int credit(int expected, int stackCountBeforePickup, int pickedAmount,
                             int harvested, int pickedUp, int planned) {
        if (expected != 1 || expected != stackCountBeforePickup
                || pickedAmount <= 0 || pickedAmount > stackCountBeforePickup
                || pickedUp < 0 || harvested < pickedUp || planned < harvested
                || planned > MAX_PLANNED_LOGS) {
            return 0;
        }
        return Math.min(Math.min(expected, pickedAmount), harvested - pickedUp);
    }
}
