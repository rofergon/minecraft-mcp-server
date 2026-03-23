package dev.yuniko.minecraftmcp.bridge;

import net.minecraft.client.MinecraftClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class ClientThreadExecutor {
    <T> T call(ClientTask<T> task) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        MinecraftClient.getInstance().execute(() -> {
            try {
                future.complete(task.run());
            } catch (Exception error) {
                future.completeExceptionally(error);
            }
        });
        return future.get(10L, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    interface ClientTask<T> {
        T run() throws Exception;
    }
}
