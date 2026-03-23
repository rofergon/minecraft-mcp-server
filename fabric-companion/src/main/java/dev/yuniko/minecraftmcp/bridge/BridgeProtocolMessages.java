package dev.yuniko.minecraftmcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.Set;

final class BridgeProtocolMessages {
    private BridgeProtocolMessages() {
    }

    static JsonObject hello(String protocolVersion, String bridgeVersion) {
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("protocolVersion", protocolVersion);
        hello.addProperty("bridgeVersion", bridgeVersion);
        return hello;
    }

    static JsonObject capabilities(
        String protocolVersion,
        String bridgeVersion,
        String minecraftVersion,
        String loader,
        String loaderVersion,
        boolean worldReady,
        Iterable<String> supportedActions,
        Iterable<String> notes
    ) {
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("type", "capabilities");
        capabilities.addProperty("protocolVersion", protocolVersion);
        capabilities.addProperty("bridgeVersion", bridgeVersion);
        capabilities.addProperty("minecraftVersion", minecraftVersion);
        capabilities.addProperty("loader", loader);
        capabilities.addProperty("loaderVersion", loaderVersion);
        capabilities.addProperty("worldReady", worldReady);
        capabilities.add("supportedActions", toStringArray(supportedActions));
        capabilities.add("notes", toStringArray(notes));
        return capabilities;
    }

    static JsonObject sessionState(boolean worldReady, boolean connected, String playerName, String dimension) {
        JsonObject state = new JsonObject();
        state.addProperty("type", "session_state");
        state.addProperty("worldReady", worldReady);
        state.addProperty("connected", connected);
        if (playerName != null) {
            state.addProperty("playerName", playerName);
        }
        if (dimension != null) {
            state.addProperty("dimension", dimension);
        }
        return state;
    }

    static JsonObject registrySnapshot(
        Iterable<Identifier> blockIds,
        Iterable<Identifier> itemIds,
        Iterable<Identifier> entityIds
    ) {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("type", "registry_snapshot");
        snapshot.add("blocks", toIdentifierArray(blockIds));
        snapshot.add("items", toIdentifierArray(itemIds));
        snapshot.add("entities", toIdentifierArray(entityIds));
        snapshot.add("namespaces", collectNamespaces(blockIds, itemIds, entityIds));
        return snapshot;
    }

    static JsonObject actionResult(String requestId, String message, JsonElement data) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "action_result");
        result.addProperty("requestId", requestId);
        result.addProperty("message", message);
        result.add("data", data);
        result.addProperty("isError", false);
        return result;
    }

    static JsonObject error(String requestId, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        if (requestId != null) {
            error.addProperty("requestId", requestId);
        }
        error.addProperty("message", message);
        return error;
    }

    static JsonObject chatEvent(String channel, String message) {
        JsonObject progress = new JsonObject();
        progress.addProperty("type", "chat_event");
        progress.addProperty("username", channel);
        progress.addProperty("message", message);
        return progress;
    }

    private static JsonArray toIdentifierArray(Iterable<Identifier> identifiers) {
        JsonArray array = new JsonArray();
        for (Identifier id : identifiers) {
            array.add(id.toString());
        }
        return array;
    }

    private static JsonArray toStringArray(Iterable<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonArray collectNamespaces(
        Iterable<Identifier> blockIds,
        Iterable<Identifier> itemIds,
        Iterable<Identifier> entityIds
    ) {
        Set<String> namespaces = new HashSet<>();
        blockIds.forEach(id -> namespaces.add(id.getNamespace()));
        itemIds.forEach(id -> namespaces.add(id.getNamespace()));
        entityIds.forEach(id -> namespaces.add(id.getNamespace()));

        JsonArray out = new JsonArray();
        namespaces.stream().sorted().forEach(out::add);
        return out;
    }
}
