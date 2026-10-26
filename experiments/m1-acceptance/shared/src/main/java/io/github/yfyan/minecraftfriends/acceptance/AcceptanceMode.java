package io.github.yfyan.minecraftfriends.acceptance;

public enum AcceptanceMode {
    STANDARD, PEER, EVENTS, NEGATIVE;
    public static final AcceptanceMode CURRENT = select();
    private static AcceptanceMode select() {
        AcceptanceMode selected = STANDARD;
        int count = 0;
        if (Boolean.getBoolean("mf.acceptance.peer")) { selected = PEER; count++; }
        if (Boolean.getBoolean("mf.acceptance.events")) { selected = EVENTS; count++; }
        if (Boolean.getBoolean("mf.acceptance.negative")) { selected = NEGATIVE; count++; }
        if (count > 1) { throw new IllegalStateException("Choose one isolated acceptance mode"); }
        return selected;
    }
}
