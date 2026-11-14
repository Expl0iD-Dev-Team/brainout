package com.desertkun.brainout.server.http;

import com.badlogic.gdx.utils.ObjectMap;
import com.desertkun.brainout.BrainOutServer;
import com.desertkun.brainout.client.Client;
import com.desertkun.brainout.client.ClientList;
import com.desertkun.brainout.client.PlayerClient;
import com.desertkun.brainout.data.Map;
import com.desertkun.brainout.mode.GameMode;
import com.desertkun.brainout.server.ServerController;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * GET /status — a small read-only snapshot for the control panel:
 * {@code {"mode": ..., "currentMap": ..., "count": N, "players": [...]}}.
 *
 * Game state is read on the game thread (via PostRunnable) to avoid racing the
 * simulation; the HTTP thread waits briefly for the result.
 */
public class StatusHandler implements HttpHandler
{
    @Override
    public void handle(HttpExchange httpExchange) throws IOException
    {
        final StringBuilder json = new StringBuilder();
        final CountDownLatch latch = new CountDownLatch(1);

        BrainOutServer.PostRunnable(() ->
        {
            try
            {
                json.append(buildStatusJson());
            }
            catch (Exception e)
            {
                json.setLength(0);
                json.append("{\"error\":\"status failed\"}");
            }
            finally
            {
                latch.countDown();
            }
        });

        try
        {
            latch.await(2, TimeUnit.SECONDS);
        }
        catch (InterruptedException ignored)
        {
            Thread.currentThread().interrupt();
        }

        byte[] body = (json.length() > 0 ? json.toString() : "{\"error\":\"timeout\"}")
                .getBytes(StandardCharsets.UTF_8);

        httpExchange.getResponseHeaders().add("Content-Type", "application/json");
        httpExchange.sendResponseHeaders(200, body.length);
        httpExchange.getResponseBody().write(body);
        httpExchange.close();
    }

    private static String buildStatusJson()
    {
        ServerController c = BrainOutServer.Controller;

        List<String> players = new ArrayList<>();
        ClientList clients = c != null ? c.getClients() : null;
        if (clients != null)
        {
            for (ObjectMap.Entry<Integer, Client> entry : clients)
            {
                if (entry.value instanceof PlayerClient)
                {
                    String name;
                    try { name = ((PlayerClient) entry.value).getName(); }
                    catch (Exception e) { name = null; }
                    players.add(name != null ? name : "?");
                }
            }
        }

        GameMode gameMode = c != null ? c.getGameMode() : null;
        String mode = (gameMode != null && gameMode.getID() != null) ? gameMode.getID().name() : null;

        Map defaultMap = Map.GetDefault();
        String currentMap = defaultMap != null ? defaultMap.getName() : null;

        StringBuilder sb = new StringBuilder();
        sb.append("{\"mode\":").append(jsonStr(mode));
        sb.append(",\"currentMap\":").append(jsonStr(currentMap));
        sb.append(",\"count\":").append(players.size());
        sb.append(",\"players\":[");
        for (int i = 0; i < players.size(); i++)
        {
            if (i > 0) sb.append(',');
            sb.append(jsonStr(players.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String jsonStr(String s)
    {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++)
        {
            char ch = s.charAt(i);
            switch (ch)
            {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
