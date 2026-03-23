package dev.yuniko.minecraftmcp.bridge;

import net.minecraft.client.MinecraftClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class ClientThreadExecutor {
    private static final long DEFAULT_TIMEOUT_SECONDS = 10L;

    <T> T call(ClientTask<T> task) throws Exception {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.isOnThread()) {
            return task.run();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        client.execute(() -> {
            try {
                future.complete(task.run());
            } catch (Exception error) {
                future.completeExceptionally(error);
            }
        });
        return future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    void run(VoidTask task) throws Exception {
        call(() -> {
            task.run();
            return null;
        });
    }

    @FunctionalInterface
    interface ClientTask<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    interface VoidTask {
        void run() throws Exception;
    }
}
