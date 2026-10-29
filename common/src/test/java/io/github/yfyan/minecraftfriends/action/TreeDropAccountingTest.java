package io.github.yfyan.minecraftfriends.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeDropAccountingTest {
    @Test
    void onlyANewSingleLogEstablishesProvenance() {
        assertTrue(TreeDropAccounting.isNewSingleLog(0, 1));
        assertFalse(TreeDropAccounting.isNewSingleLog(0, 0));
        assertFalse(TreeDropAccounting.isNewSingleLog(0, 2));
        assertFalse(TreeDropAccounting.isNewSingleLog(1, 1));
        assertFalse(TreeDropAccounting.isNewSingleLog(1, 2));
        assertFalse(TreeDropAccounting.isNewSingleLog(63, 64));
        assertFalse(TreeDropAccounting.isNewSingleLog(-1, 1));
        assertFalse(TreeDropAccounting.isNewSingleLog(0, -1));
    }

    @Test
    void creditsOnlyOneActualPickupOfTheTrackedSingleLog() {
        assertEquals(1, TreeDropAccounting.credit(1, 1, 1, 1, 0, 4));
        assertEquals(1, TreeDropAccounting.credit(1, 1, 1, 4, 3, 4));
        assertEquals(1, TreeDropAccounting.credit(1, 1, 1, 8, 7, 8));
    }

    @Test
    void untrackedAndMergedStacksNeverReceiveCredit() {
        assertEquals(0, TreeDropAccounting.credit(0, 1, 1, 1, 0, 4));
        assertEquals(0, TreeDropAccounting.credit(1, 2, 1, 1, 0, 4));
        assertEquals(0, TreeDropAccounting.credit(1, 64, 64, 1, 0, 4));
        assertEquals(0, TreeDropAccounting.credit(2, 2, 2, 2, 0, 4));
        // Even a partial pickup of a two-item tracked stack cannot prove which log was picked up.
        assertEquals(0, TreeDropAccounting.credit(2, 2, 1, 2, 0, 4));
    }

    @Test
    void refusesZeroNegativeOrOverflowingPickupAmounts() {
        assertEquals(0, TreeDropAccounting.credit(1, 1, 0, 1, 0, 4));
        assertEquals(0, TreeDropAccounting.credit(1, 1, -1, 1, 0, 4));
        assertEquals(0, TreeDropAccounting.credit(1, 1, 2, 1, 0, 4));
        assertEquals(0, TreeDropAccounting.credit(1, 1, Integer.MAX_VALUE, 1, 0, 4));
        assertEquals(0, TreeDropAccounting.credit(1, -1, 1, 1, 0, 4));
        assertEquals(0, TreeDropAccounting.credit(-1, 1, 1, 1, 0, 4));
    }

    @Test
    void neverCreditsMoreThanActuallyHarvestedOrCreditsAPickupTwice() {
        assertEquals(0, TreeDropAccounting.credit(1, 1, 1, 0, 0, 4));
        int credited = TreeDropAccounting.credit(1, 1, 1, 1, 0, 4);
        assertEquals(1, credited);
        assertEquals(0, TreeDropAccounting.credit(1, 1, 1, 1, credited, 4));
        assertEquals(0, TreeDropAccounting.credit(1, 1, 1, 4, 4, 4));
        assertEquals(0, TreeDropAccounting.credit(1, 1, 1, 0, 0, 0));
    }

    @Test
    void impossibleOrOutOfBudgetCountersFailClosed() {
        assertEquals(0, TreeDropAccounting.credit(1, 1, 1, 1, -1, 4));
        assertEquals(0, TreeDropAccounting.credit(1, 1, 1, -1, 0, 4));
        assertEquals(0, TreeDropAccounting.credit(1, 1, 1, 1, 2, 4));
        assertEquals(0, TreeDropAccounting.credit(1, 1, 1, 5, 0, 4));
        assertEquals(0, TreeDropAccounting.credit(1, 1, 1, 1, 0, -1));
        assertEquals(0, TreeDropAccounting.credit(1, 1, 1, 1, 0, 9));
        assertEquals(0, TreeDropAccounting.credit(1, 1, 1,
                Integer.MAX_VALUE, Integer.MAX_VALUE - 1, Integer.MAX_VALUE));
    }
}
