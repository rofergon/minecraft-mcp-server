package dev.yuniko.minecraftmcp.bridge;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftMcpBridgeModDigCandidateTest {
    @Test
    void maxDigCandidateVerticalDropCoversTallSurvivalLogs() {
        assertEquals(6, MinecraftMcpBridgeMod.maxDigCandidateVerticalDrop(4.5D, 1.62D));
    }

    @Test
    void enumerateDigCandidateFeetPositionsIncludesStandingDirectlyBelowTheTarget() {
        BlockPos target = new BlockPos(10, 70, -3);

        List<BlockPos> candidates = MinecraftMcpBridgeMod.enumerateDigCandidateFeetPositions(target, 6);

        assertTrue(candidates.contains(new BlockPos(10, 64, -3)));
        assertTrue(candidates.contains(new BlockPos(10, 70, -3)));
    }

    @Test
    void enumerateDigCandidateFeetPositionsStillIncludesAdjacentFallbackSpots() {
        BlockPos target = new BlockPos(10, 70, -3);

        List<BlockPos> candidates = MinecraftMcpBridgeMod.enumerateDigCandidateFeetPositions(target, 6);

        assertTrue(candidates.contains(new BlockPos(11, 68, -3)));
        assertTrue(candidates.contains(new BlockPos(9, 69, -4)));
    }
}
