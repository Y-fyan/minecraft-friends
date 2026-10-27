package io.github.yfyan.minecraftfriends.action;
/** Process-level advanced navigation setting; no world or credential access. */
public final class ActionTuning {
    public static final String FOLLOW_DISTANCE_PROPERTY = "minecraftfriends.actions.follow-distance";
    private ActionTuning() { }
    public static double followDistance(String value) {
        if(value!=null)try{double n=Double.parseDouble(value);if(Double.isFinite(n)&&n>=2&&n<=8)return n;}catch(NumberFormatException ignored) { }
        return 3.0;
    }
    public static int followGoalRadius(double distance) { return Math.max(0,(int)Math.floor(distance-1.5)); }
}
