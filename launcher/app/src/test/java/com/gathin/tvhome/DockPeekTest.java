package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DockPeekTest {
    @Test public void unknownOrMissingModesKeepTheDockInPlace() {
        assertEquals(DockPeek.NONE, DockPeek.mode(null));
        assertEquals(DockPeek.NONE, DockPeek.mode("ultar"));
        assertEquals(DockPeek.ULTRA, DockPeek.mode(" Ultra "));
        assertEquals(DockPeek.BALANCED, DockPeek.mode("balanced"));
    }

    @Test public void ultraLeavesASliverOfTheBandOnScreen() {
        // 1080p at density 2: a 280px band starting at 720px; 60px of it stays above the bottom edge.
        assertEquals(1080f - 60f - 720f, DockPeek.offset(DockPeek.ULTRA, 1080, 720, 280, 2f), 0f);
    }

    @Test public void balancedLeavesHalfTheBand() {
        assertEquals(1080f - 140f - 720f, DockPeek.offset(DockPeek.BALANCED, 1080, 720, 280, 2f), 0f);
    }

    @Test public void noneOrADockAlreadyLowEnoughDoesNotMove() {
        assertEquals(0f, DockPeek.offset(DockPeek.NONE, 1080, 720, 280, 2f), 0f);
        assertEquals(0f, DockPeek.offset(DockPeek.ULTRA, 1080, 1060, 280, 2f), 0f);
        assertEquals(0f, DockPeek.offset(DockPeek.ULTRA, 1080, 720, 0, 2f), 0f);
    }

    @Test public void settingsCycleThroughEveryMode() {
        assertEquals(DockPeek.BALANCED, DockPeek.next(DockPeek.NONE));
        assertEquals(DockPeek.ULTRA, DockPeek.next(DockPeek.BALANCED));
        assertEquals(DockPeek.NONE, DockPeek.next(DockPeek.ULTRA));
    }
}
