package dev.yuniko.minecraftmcp.bridge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BridgePlacementSupportTest {
    @Test
    void orderedFaceDirectionsPrefersRequestedFace() {
        assertEquals(
            List.of("west", "down", "north", "south", "east", "up"),
            BridgePlacementSupport.orderedFaceDirections("west")
        );
    }

    @Test
    void orderedFaceDirectionsFallsBackToDefaultOrder() {
        assertEquals(
            List.of("down", "north", "south", "east", "west", "up"),
            BridgePlacementSupport.orderedFaceDirections(null)
        );
    }

    @Test
    void torchPlacementAcceptsFloorAndWallVariants() {
        assertEquals(Set.of("torch", "wall_torch"), BridgePlacementSupport.expectedPlacedBlockIds("minecraft:torch"));
        assertTrue(BridgePlacementSupport.placementConfirmed("torch", "minecraft:wall_torch"));
        assertTrue(BridgePlacementSupport.placementConfirmed("torch", "minecraft:torch"));
    }
}
