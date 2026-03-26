package dev.yuniko.minecraftmcp.bridge;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TaskServicesTest {
    @Test
    void mineCobblestoneRequiresWoodenPickaxeInInventoryFirst() {
        FakeHooks hooks = new FakeHooks();
        hooks.pickaxePrepared = false;
        TaskServices services = new TaskServices(hooks);

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> services.mineCobblestoneSync(1, 16, 5)
        );

        assertEquals(
            "Cannot mine stone yet. Craft at least one wooden_pickaxe and select it in hotbar slot 1 before calling mine-cobblestone.",
            error.getMessage()
        );
        assertEquals(1, hooks.ensureItemSelectedCalls);
        assertEquals(0, hooks.findNearestMineTargetCalls);
    }

    @Test
    void mineCobblestonePreparesWoodenPickaxeInHotbarSlotOneBeforeMining() throws Exception {
        FakeHooks hooks = new FakeHooks();
        hooks.pickaxePrepared = true;
        TaskServices services = new TaskServices(hooks);

        TaskServices.MineCobblestoneResult result = services.mineCobblestoneSync(1, 16, 1);

        assertEquals(1, hooks.ensureItemSelectedCalls);
        assertEquals("wooden_pickaxe", hooks.lastEnsuredItem);
        assertEquals(0, hooks.lastEnsuredHotbarSlot);
        assertEquals(1, hooks.findNearestMineTargetCalls);
        assertEquals(1, hooks.dugBlocks);
        assertEquals(1, result.requested());
        assertEquals(1, result.collected());
        assertEquals(1, result.processed());
        assertEquals("cobblestone", result.block());
        assertEquals(hooks.playerPos, result.position());
    }

    private static final class FakeHooks implements TaskServices.Hooks {
        private final BlockPos playerPos = new BlockPos(10, 64, 10);
        private final BlockPos mineTargetPos = new BlockPos(12, 64, 10);
        private final List<String> progressMessages = new ArrayList<>();

        private boolean pickaxePrepared;
        private int ensureItemSelectedCalls;
        private String lastEnsuredItem;
        private int lastEnsuredHotbarSlot = -1;
        private int findNearestMineTargetCalls;
        private int dugBlocks;

        @Override
        public void digBlockSync(BlockPos pos) {
            assertEquals(mineTargetPos, pos);
            dugBlocks++;
        }

        @Override
        public void moveToPosition(Vec3d target, double range, long timeoutMs, boolean allowJump, boolean clearSoftObstructions) {
        }

        @Override
        public int countMatchingLogsInInventory(String preferredType) {
            return 0;
        }

        @Override
        public int countMatchingInventoryItems(String targetItem) {
            if ("cobblestone".equals(targetItem)) {
                return dugBlocks;
            }
            return 0;
        }

        @Override
        public boolean ensureItemSelectedInHotbar(String targetItem, int hotbarSlot) {
            ensureItemSelectedCalls++;
            lastEnsuredItem = targetItem;
            lastEnsuredHotbarSlot = hotbarSlot;
            return pickaxePrepared;
        }

        @Override
        public TaskServices.HarvestTarget findNearestHarvestTarget(String preferredType, int maxRadius, Set<BlockPos> ignoredTargets) {
            return null;
        }

        @Override
        public TaskServices.MineTarget findNearestMineTarget(Set<String> blockTypes, int maxRadius, Set<BlockPos> ignoredTargets) {
            findNearestMineTargetCalls++;
            if (dugBlocks > 0) {
                return null;
            }
            return new TaskServices.MineTarget(mineTargetPos, "stone");
        }

        @Override
        public BlockPos currentPlayerBlockPos() {
            return playerPos;
        }

        @Override
        public void sendJobProgress(String channel, String message) {
            progressMessages.add(channel + ":" + message);
        }
    }
}
