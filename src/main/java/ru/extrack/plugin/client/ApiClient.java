package ru.extrack.plugin.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import ru.extrack.plugin.util.Json;

/**
 * Blocking JSON over HTTPS on top of HttpURLConnection: java.net.http needs Java 11,
 * and 1.16.5 servers often run on 8. Called only from the plugin's own threads.
 */
public final class ApiClient {

    private static final int GZIP_THRESHOLD  = 8 * 1024;
    private static final int CONNECT_TIMEOUT = 5_000;

    private final String base;
    private final String authorization;
    private final String userAgent;
    private final Gson   gson = new Gson();

    public ApiClient(String url, String token, String userAgent) {
        this.base = url + "/api/plugin/v1";
        this.authorization = "Bearer " + token;
        this.userAgent = userAgent;
    }

    @FunctionalInterface
    public interface Body {

        void write(JsonWriter out) throws IOException;
    }

    public JsonObject get(String path, int timeout, AtomicReference<HttpURLConnection> handle) throws ApiException {
        return this.execute("GET", path, null, timeout, handle);
    }

    public JsonObject post(String path, JsonElement body, int timeout) throws ApiException {
        return this.post(path, out -> this.gson.toJson(body, out), timeout);
    }

    public JsonObject post(String path, Body body, int timeout) throws ApiException {
        return this.execute("POST", path, encode(body), timeout, null);
    }

    private JsonObject execute(String method, String path, byte[] body, int timeout, AtomicReference<HttpURLConnection> handle) throws ApiException {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(this.base + path).openConnection();
            if (handle != null) handle.set(connection);
            connection.setRequestMethod(method);
            // a redirect would carry the token to wherever it points
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(Math.min(CONNECT_TIMEOUT, timeout));
            connection.setReadTimeout(timeout);
            connection.setRequestProperty("Authorization", this.authorization);
            connection.setRequestProperty("User-Agent", this.userAgent);
            connection.setRequestProperty("Accept", "application/json");

            if (body != null) {
                byte[] payload = body;
                if (payload.length > GZIP_THRESHOLD) {
                    payload = gzip(payload);
                    connection.setRequestProperty("Content-Encoding", "gzip");
                }
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }

            int status = connection.getResponseCode();
            JsonObject json = this.read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status >= 200 && status < 300) return json;
            throw error(status, json, connection.getHeaderField("Retry-After"));
        }
        catch (IOException exception) {
            throw ApiException.network(exception);
        }
        finally {
            if (handle != null) handle.set(null);
        }
    }

    private JsonObject read(InputStream in) throws IOException {
        if (in == null) return new JsonObject();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonElement element = this.gson.fromJson(reader, JsonElement.class);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        }
        catch (JsonParseException exception) {
            return new JsonObject();
        }
    }

    private static ApiException error(int status, JsonObject json, String retryAfter) {
        JsonObject error = Json.object(json, "error");
        String code = Json.string(error, "code");
        String message = Json.string(error, "message");
        if (message == null) {
            message = status >= 300 && status < 400
                ? "панель ответила перенаправлением (HTTP " + status + "), укажите в url адрес с https://"
                : "HTTP " + status;
        }
        int seconds = 0;
        if (retryAfter != null) {
            try {
                seconds = Integer.parseInt(retryAfter.trim());
            }
            catch (NumberFormatException ignored) {
                // HTTP date form, the default backoff is fine
            }
        }
        return new ApiException(status, code == null ? "http_" + status : code, message, seconds);
    }

    private static byte[] encode(Body body) throws ApiException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        try (JsonWriter writer = new JsonWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8))) {
            body.write(writer);
        }
        catch (IOException | IllegalStateException exception) {
            throw new ApiException(0, "encode", "не удалось собрать запрос: " + exception.getMessage(), 0);
        }
        return buffer.toByteArray();
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(raw.length / 4);
        try (GZIPOutputStream out = new GZIPOutputStream(buffer)) {
            out.write(raw);
        }
        return buffer.toByteArray();
    }
}
