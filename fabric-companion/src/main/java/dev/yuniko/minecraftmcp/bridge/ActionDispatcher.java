package dev.yuniko.minecraftmcp.bridge;

import com.google.gson.JsonObject;

final class ActionDispatcher {
    interface Handler {
        void onActionRequest(ProtocolSession session, String requestId, String action, JsonObject args);
        void onHello(ProtocolSession session);
        void onProtocolError(ProtocolSession session, String requestId, String message);
        boolean isTokenValid(JsonObject helloMessage);
    }

    private final Handler handler;

    ActionDispatcher(Handler handler) {
        this.handler = handler;
    }

    void dispatch(ProtocolSession session, JsonObject message) {
        String type = getAsString(message, "type");
        if (type == null) {
            return;
        }

        if (type.equals("hello")) {
            if (!handler.isTokenValid(message)) {
                handler.onProtocolError(session, null, "Bridge token validation failed.");
                return;
            }

            handler.onHello(session);
            return;
        }

        if (type.equals("action_request")) {
            String requestId = getAsString(message, "requestId");
            String action = getAsString(message, "action");
            JsonObject args = message.has("args") && message.get("args").isJsonObject()
                ? message.getAsJsonObject("args")
                : new JsonObject();

            if (requestId == null || action == null) {
                handler.onProtocolError(session, requestId, "Malformed action_request message.");
                return;
            }

            handler.onActionRequest(session, requestId, action, args);
        }
    }

    private static String getAsString(JsonObject object, String memberName) {
        if (!object.has(memberName) || object.get(memberName).isJsonNull()) {
            return null;
        }
        return object.get(memberName).getAsString();
    }
}
