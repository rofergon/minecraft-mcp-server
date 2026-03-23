package dev.yuniko.minecraftmcp.bridge;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

record BridgeConfig(String host, int port, String token) {
    private static final Gson GSON = new Gson();

    static BridgeConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("minecraft-mcp-bridge.json");
        if (!Files.exists(path)) {
            return new BridgeConfig("127.0.0.1", 25570, null);
        }

        try {
            return GSON.fromJson(Files.readString(path), BridgeConfig.class);
        } catch (IOException e) {
            e.printStackTrace();
            return new BridgeConfig("127.0.0.1", 25570, null);
        }
    }
}
