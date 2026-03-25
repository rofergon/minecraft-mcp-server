package dev.yuniko.minecraftmcp.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NavigationControllerTest {
    @Test
    void effectiveArrivalRangePreventsTinyTargetPingPong() {
        assertEquals(0.35D, NavigationController.effectiveArrivalRange(0.0D));
        assertEquals(0.35D, NavigationController.effectiveArrivalRange(0.2D));
        assertEquals(0.8D, NavigationController.effectiveArrivalRange(0.8D));
    }

    @Test
    void preferLeftRecoveryChoosesTheMoreOpenSide() {
        assertTrue(NavigationController.preferLeftRecovery(3.5D, 1.0D, false));
        assertFalse(NavigationController.preferLeftRecovery(1.0D, 3.5D, true));
    }

    @Test
    void preferLeftRecoveryKeepsPreviousSideWhenBothDirectionsLookEquivalent() {
        assertTrue(NavigationController.preferLeftRecovery(2.05D, 2.0D, true));
        assertFalse(NavigationController.preferLeftRecovery(2.0D, 2.05D, false));
    }
}
