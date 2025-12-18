package com.osuplayer.mirrors;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osuplayer.downloads.DownloadProgressListener;

public final class MirrorHttp {

    public static final String USER_AGENT = "Osulux/1.0 (mirror)";

    private static final HttpClient DEFAULT_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private static final HttpClient INSECURE_CLIENT = buildInsecureClient();

    private MirrorHttp() {}

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static JsonArray getJsonArray(String url) throws IOException {
        return getJsonArray(url, false);
    }

    public static JsonArray getJsonArray(String url, boolean insecure) throws IOException {
        String body = getString(url, insecure);
        return JsonParser.parseString(body).getAsJsonArray();
    }

    public static JsonObject getJsonObject(String url) throws IOException {
        return getJsonObject(url, false);
    }

    public static JsonObject getJsonObject(String url, boolean insecure) throws IOException {
        String body = getString(url, insecure);
        return JsonParser.parseString(body).getAsJsonObject();
    }

    public static String getString(String url) throws IOException {
        return getString(url, false);
    }

    public static String getString(String url, boolean insecure) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = send(request, insecure);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " al consultar " + url);
        }
        return response.body();
    }

    public static Path download(String url, String prefix) throws IOException {
        return download(url, prefix, false);
    }

    public static Path download(String url, String prefix, boolean insecure) throws IOException {
        return downloadWithProgress(url, prefix, insecure, null, -1, null);
    }

    public static Path download(String url, String prefix, String referer) throws IOException {
        return downloadWithProgress(url, prefix, false, referer, -1, null);
    }

    public static Path downloadWithProgress(String url,
                                            String prefix,
                                            boolean insecure,
                                            String referer,
                                            long beatmapsetId,
                                            DownloadProgressListener listener) throws IOException {
        Path file = Files.createTempFile(prefix, ".osz");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET();
        if (referer != null && !referer.isBlank()) {
            builder.header("Referer", referer);
        }
        HttpResponse<InputStream> response = sendToStream(builder.build(), insecure);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            try (InputStream input = response.body();
                 OutputStream output = Files.newOutputStream(file)) {
                copyWithProgress(input, output, beatmapsetId, total, listener);
                return file;
            } catch (IOException ex) {
                Files.deleteIfExists(file);
                throw ex;
            }
        }
        Files.deleteIfExists(file);
        throw new IOException("HTTP " + response.statusCode() + " al descargar " + url);
    }

    private static HttpResponse<String> send(HttpRequest request, boolean insecure) throws IOException {
        try {
            return (insecure ? INSECURE_CLIENT : DEFAULT_CLIENT)
                    .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Petición interrumpida", e);
        }
    }

    private static HttpResponse<InputStream> sendToStream(HttpRequest request, boolean insecure) throws IOException {
        try {
            return (insecure ? INSECURE_CLIENT : DEFAULT_CLIENT)
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Descarga interrumpida", e);
        }
    }

    private static void copyWithProgress(InputStream input,
                                         OutputStream output,
                                         long beatmapsetId,
                                         long totalBytes,
                                         DownloadProgressListener listener) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long downloaded = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            downloaded += read;
            if (listener != null) {
                listener.onProgress(beatmapsetId, downloaded, totalBytes);
            }
        }
    }

    private static HttpClient buildInsecureClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("");
            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .sslParameters(sslParameters)
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IllegalStateException("No se pudo crear el cliente inseguro", e);
        }
    }
}
