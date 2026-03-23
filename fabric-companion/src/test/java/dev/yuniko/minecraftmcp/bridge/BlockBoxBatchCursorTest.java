package dev.yuniko.minecraftmcp.bridge;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlockBoxBatchCursorTest {
    @Test
    void nextBatchCoversTheWholeVolumeWithoutDuplicates() {
        BlockPos origin = new BlockPos(10, 20, 30);
        BlockBoxBatchCursor cursor = new BlockBoxBatchCursor(origin, -1, 1, -1, 0, 0, 1);
        Set<BlockPos> seen = new HashSet<>();
        Set<BlockPos> expected = new HashSet<>();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 0; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    expected.add(origin.add(dx, dy, dz));
                }
            }
        }

        while (cursor.hasRemaining()) {
            List<BlockPos> batch = cursor.nextBatch(5);
            assertFalse(batch.isEmpty());
            assertTrue(batch.size() <= 5);
            seen.addAll(batch);
        }

        assertEquals(expected, seen);
        assertFalse(cursor.hasRemaining());
        assertTrue(cursor.nextBatch(5).isEmpty());
    }

    @Test
    void nextBatchRejectsNonPositiveBatchSizes() {
        BlockBoxBatchCursor cursor = new BlockBoxBatchCursor(BlockPos.ORIGIN, 0, 0, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> cursor.nextBatch(0));
    }
}
