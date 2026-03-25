package dev.yuniko.minecraftmcp.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ActionContextTest {
    @Test
    void sendsResultsErrorsAndProgressThroughTheProvidedSession() throws Exception {
        try (
            ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            Socket clientSocket = new Socket("127.0.0.1", serverSocket.getLocalPort());
            Socket serverSideSocket = serverSocket.accept();
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter writer = new PrintWriter(serverSideSocket.getOutputStream(), true, StandardCharsets.UTF_8)
        ) {
            ProtocolSession session = new ProtocolSession(serverSideSocket, writer, new Gson());
            ActionContext context = new ActionContext(session, "req-42");

            JsonObject data = new JsonObject();
            data.addProperty("ok", true);
            context.sendActionResult("done", data);

            JsonObject actionResult = JsonParser.parseString(reader.readLine()).getAsJsonObject();
            assertEquals("action_result", actionResult.get("type").getAsString());
            assertEquals("req-42", actionResult.get("requestId").getAsString());
            assertEquals("done", actionResult.get("message").getAsString());
            assertEquals(true, actionResult.getAsJsonObject("data").get("ok").getAsBoolean());

            context.sendError("boom");

            JsonObject error = JsonParser.parseString(reader.readLine()).getAsJsonObject();
            assertEquals("error", error.get("type").getAsString());
            assertEquals("req-42", error.get("requestId").getAsString());
            assertEquals("boom", error.get("message").getAsString());

            context.sendJobProgress("harvest-wood", "3/10");

            JsonObject progress = JsonParser.parseString(reader.readLine()).getAsJsonObject();
            assertEquals("chat_event", progress.get("type").getAsString());
            assertEquals("harvest-wood", progress.get("username").getAsString());
            assertEquals("3/10", progress.get("message").getAsString());
        }
    }
}
