package io.github.yfyan.minecraftfriends.action;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ActionTuningTest {
 @Test void boundsInvalidOrUnboundedSettings(){for(String v:new String[]{null,"NaN","Infinity","1.9","8.1","bad"})assertEquals(3.0,ActionTuning.followDistance(v));assertEquals(2,ActionTuning.followDistance("2"));assertEquals(8,ActionTuning.followDistance("8"));assertEquals(3.5,ActionTuning.followDistance("3.5"));}
 @Test void goalLeavesRoomForActualBodyAndVerticalSeparation(){assertEquals(0,ActionTuning.followGoalRadius(2));assertEquals(1,ActionTuning.followGoalRadius(3));assertEquals(6,ActionTuning.followGoalRadius(8));}
}
