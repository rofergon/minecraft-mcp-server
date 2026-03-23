package dev.yuniko.minecraftmcp.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

final class BridgeServer {
    interface Listener {
        void onMessage(ProtocolSession session, JsonObject message);
        void onSessionClosed(ProtocolSession session);
        void onSessionOpened(ProtocolSession session);
        void onTransportError(String context, Exception error);
    }

    private final BridgeConfig config;
    private final ExecutorService executor;
    private final Listener listener;
    private final Gson gson = new Gson();
    private volatile ProtocolSession activeSession;

    BridgeServer(BridgeConfig config, ExecutorService executor, Listener listener) {
        this.config = config;
        this.executor = executor;
        this.listener = listener;
    }

    void start() {
        executor.submit(() -> {
            try (ServerSocket serverSocket = new ServerSocket(config.port(), 1, InetAddress.getByName(config.host()))) {
                System.out.println("[minecraft-mcp-bridge] Listening on " + config.host() + ":" + config.port());
                while (!Thread.currentThread().isInterrupted()) {
                    Socket socket = serverSocket.accept();
                    System.out.println("[minecraft-mcp-bridge] Accepted connection from " + socket.getRemoteSocketAddress());
                    executor.submit(() -> handleClient(socket));
                }
            } catch (IOException e) {
                listener.onTransportError(
                    "Failed to start bridge server on " + config.host() + ":" + config.port(),
                    e
                );
            }
        });
    }

    ProtocolSession getActiveSession() {
        return activeSession;
    }

    private void handleClient(Socket socket) {
        closeActiveSession();
        ProtocolSession session = null;

        try (
            socket;
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)
        ) {
            session = new ProtocolSession(socket, writer, gson);
            activeSession = session;
            listener.onSessionOpened(session);

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                try {
                    listener.onMessage(session, JsonParser.parseString(line).getAsJsonObject());
                } catch (RuntimeException error) {
                    listener.onTransportError("Failed to parse bridge message: " + line, error);
                }
            }
        } catch (IOException e) {
            listener.onTransportError("Client bridge connection failed", e);
        } finally {
            if (session != null) {
                if (activeSession == session) {
                    activeSession = null;
                }
                listener.onSessionClosed(session);
            }
        }
    }

    private void closeActiveSession() {
        ProtocolSession current = activeSession;
        if (current == null) {
            return;
        }

        activeSession = null;
        current.close();
    }
}
