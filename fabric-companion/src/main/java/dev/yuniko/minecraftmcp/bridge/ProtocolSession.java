package dev.yuniko.minecraftmcp.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;

final class ProtocolSession implements AutoCloseable {
    private final Socket socket;
    private final PrintWriter writer;
    private final Gson gson;

    ProtocolSession(Socket socket, PrintWriter writer, Gson gson) {
        this.socket = socket;
        this.writer = writer;
        this.gson = gson;
    }

    String remoteAddress() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    void send(JsonObject payload) {
        synchronized (writer) {
            writer.println(gson.toJson(payload));
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
