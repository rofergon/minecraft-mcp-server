package dev.yuniko.minecraftmcp.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftMcpBridgeModVegetationTest {
    @Test
    void clearableFoliageIdsCoverShrubsAndTreeGrowth() {
        assertTrue(MinecraftMcpBridgeMod.isClearableFoliageId("sweet_berry_bush"));
        assertTrue(MinecraftMcpBridgeMod.isClearableFoliageId("dead_bush"));
        assertTrue(MinecraftMcpBridgeMod.isClearableFoliageId("azalea"));
        assertTrue(MinecraftMcpBridgeMod.isClearableFoliageId("oak_sapling"));
        assertTrue(MinecraftMcpBridgeMod.isClearableFoliageId("mangrove_propagule"));
        assertTrue(MinecraftMcpBridgeMod.isClearableFoliageId("vine"));
    }

    @Test
    void clearableMiningObstructionIdsIncludeSoftPlantsButNotSolidTargets() {
        assertTrue(MinecraftMcpBridgeMod.isClearableMiningObstructionId("short_grass"));
        assertTrue(MinecraftMcpBridgeMod.isClearableMiningObstructionId("flowering_azalea"));
        assertFalse(MinecraftMcpBridgeMod.isClearableMiningObstructionId("oak_log"));
        assertFalse(MinecraftMcpBridgeMod.isClearableMiningObstructionId("stone"));
    }
}
