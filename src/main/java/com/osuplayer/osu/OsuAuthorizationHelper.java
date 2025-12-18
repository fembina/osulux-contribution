package com.osuplayer.osu;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

public class OsuAuthorizationHelper {

    private static final String REDIRECT_URI = "http://localhost:39152/osu-callback";
    private static final int CALLBACK_PORT = 39152;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Gson gson = new Gson();

    public String getRedirectUri() {
        return REDIRECT_URI;
    }

    public OsuApiClient.OAuthToken startAuthorization(int clientId, String clientSecret) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", CALLBACK_PORT), 0);
        CompletableFuture<Map<String, String>> responseFuture = new CompletableFuture<>();
        server.createContext("/osu-callback", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);
            byte[] body = successPage().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            responseFuture.complete(params);
        });
        server.start();

        String state = UUID.randomUUID().toString();
        String authorizeUrl = buildAuthorizeUrl(clientId, state);
        try {
            openBrowser(authorizeUrl);
            Map<String, String> params = responseFuture.get(180, TimeUnit.SECONDS);
            if (!state.equals(params.get("state"))) {
                throw new IOException("La respuesta de osu! no coincide con el estado esperado.");
            }
            if (params.containsKey("error")) {
                throw new IOException("La autorización fue cancelada: " + params.get("error"));
            }
            String code = params.get("code");
            if (code == null || code.isBlank()) {
                throw new IOException("osu! no devolvió ningún código de autorización.");
            }
            return exchangeCodeForToken(clientId, clientSecret, code);
        } catch (TimeoutException ex) {
            throw new IOException("No se recibió respuesta de osu! a tiempo. Intenta de nuevo.", ex);
        } catch (Exception ex) {
            if (ex instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("No se pudo completar el inicio de sesión", ex);
        } finally {
            server.stop(0);
        }
    }

    private String buildAuthorizeUrl(int clientId, String state) {
        return "https://osu.ppy.sh/oauth/authorize?client_id=" + clientId
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&response_type=code&scope=public&state=" + encode(state);
    }

    private OsuApiClient.OAuthToken exchangeCodeForToken(int clientId, String clientSecret, String code) throws IOException {
        String body = "client_id=" + encode(String.valueOf(clientId))
                + "&client_secret=" + encode(clientSecret)
                + "&code=" + encode(code)
                + "&grant_type=authorization_code"
                + "&redirect_uri=" + encode(REDIRECT_URI);

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://osu.ppy.sh/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new IOException("El intercambio de código por token falló: status " + response.statusCode() + " - " + response.body());
        }

        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        String accessToken = root.get("access_token").getAsString();
        String refreshToken = root.get("refresh_token").getAsString();
        long expiresIn = root.get("expires_in").getAsLong();
        return new OsuApiClient.OAuthToken(accessToken, refreshToken, expiresIn);
    }

    private void openBrowser(String url) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url));
        } else {
            throw new IOException("No se pudo abrir el navegador automáticamente. Abre esta URL manualmente: " + url);
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isBlank()) return params;
        String[] parts = query.split("&");
        for (String part : parts) {
            int idx = part.indexOf('=');
            if (idx <= 0) continue;
            String key = decode(part.substring(0, idx));
            String value = decode(part.substring(idx + 1));
            params.put(key, value);
        }
        return params;
    }

    private String successPage() {
        return "<html><body style=\"font-family:sans-serif;text-align:center;margin-top:3rem;\">"
                + "<h2>La autorización con osu! se completó.</h2>"
                + "<p>Ya puedes volver a Osulux.</p>"
                + "</body></html>";
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("La petición HTTP fue interrumpida", e);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
