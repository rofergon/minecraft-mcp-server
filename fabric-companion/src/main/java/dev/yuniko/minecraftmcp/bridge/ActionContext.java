package dev.yuniko.minecraftmcp.bridge;

import com.google.gson.JsonElement;

final class ActionContext {
    private final ProtocolSession session;
    private final String requestId;

    ActionContext(ProtocolSession session, String requestId) {
        this.session = session;
        this.requestId = requestId;
    }

    ProtocolSession session() {
        return session;
    }

    String requestId() {
        return requestId;
    }

    void sendActionResult(String message, JsonElement data) {
        session.send(BridgeProtocolMessages.actionResult(requestId, message, data));
    }

    void sendError(String message) {
        session.send(BridgeProtocolMessages.error(requestId, message));
    }

    void sendJobProgress(String channel, String message) {
        session.send(BridgeProtocolMessages.chatEvent(channel, message));
    }
}
