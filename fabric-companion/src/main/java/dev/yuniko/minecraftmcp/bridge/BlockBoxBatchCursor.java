package dev.yuniko.minecraftmcp.bridge;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

final class BlockBoxBatchCursor {
    private final BlockPos origin;
    private final int minDx;
    private final int maxDx;
    private final int minDy;
    private final int maxDy;
    private final int minDz;
    private final int maxDz;

    private int nextDx;
    private int nextDy;
    private int nextDz;
    private boolean exhausted;

    BlockBoxBatchCursor(BlockPos origin, int minDx, int maxDx, int minDy, int maxDy, int minDz, int maxDz) {
        this.origin = origin;
        this.minDx = minDx;
        this.maxDx = maxDx;
        this.minDy = minDy;
        this.maxDy = maxDy;
        this.minDz = minDz;
        this.maxDz = maxDz;
        this.nextDx = minDx;
        this.nextDy = minDy;
        this.nextDz = minDz;
    }

    boolean hasRemaining() {
        return !exhausted;
    }

    List<BlockPos> nextBatch(int maxBatchSize) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }

        if (exhausted) {
            return List.of();
        }

        List<BlockPos> batch = new ArrayList<>(maxBatchSize);
        while (!exhausted && batch.size() < maxBatchSize) {
            batch.add(origin.add(nextDx, nextDy, nextDz));
            advance();
        }
        return batch;
    }

    private void advance() {
        if (nextDz < maxDz) {
            nextDz++;
            return;
        }

        nextDz = minDz;
        if (nextDy < maxDy) {
            nextDy++;
            return;
        }

        nextDy = minDy;
        if (nextDx < maxDx) {
            nextDx++;
            return;
        }

        exhausted = true;
    }
}
