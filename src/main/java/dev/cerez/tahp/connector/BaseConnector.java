package dev.cerez.tahp.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cerez.tahp.Log;
import dev.cerez.tahp.connector.model.Symbol;
import lombok.Data;
import lombok.SneakyThrows;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public abstract class BaseConnector implements Connector {

    protected static final int MAX_STREAMS_PER_SUBSCRIBE = 100;
    protected static final int TIMEOUT = 30;
    protected static final int COOLDOWN_MS = 100;

    @NotNull  protected final ObjectMapper mapper = new ObjectMapper();
    @NotNull  protected final HttpClient clientHttp = HttpClient.newHttpClient();
    @NotNull  protected final HashMap<String, Symbol> cachedSymbols = new HashMap<>();
    @NotNull  protected final HashSet<String> pendingRequest = new HashSet<>();
    @NotNull  protected final ExecutorService executor = Executors.newFixedThreadPool(4);
    @Nullable protected       Keys apiKey;
              protected       WebSocket webSocket;

    @NotNull  private final Object streamIncomingLock = new Object();
    @NotNull  private final StringBuilder streamIncomingMessage = new StringBuilder();

    @NotNull  protected volatile boolean startWebSocket = false;

    @Data
    public abstract static class Keys{
        @NotNull private final String key;
        @NotNull private final String secret;
    }

    public void invalidedCache() {
        cachedSymbols.clear();
        pendingRequest.clear();
    }

    @Override
    public void start(){
        webSocket = clientHttp
                .newWebSocketBuilder()
                .buildAsync(URI.create(getURL().baseWws), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                        WebSocket.Listener.super.onOpen(webSocket);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        String contentToParse = accumulateMessage(data, last, streamIncomingLock, streamIncomingMessage);
                        if (contentToParse != null) {
                            handleStreamMessage(contentToParse);
                        }
                        webSocket.request(1);
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }

                    @Override
                    @SuppressWarnings("CallToPrintStackTrace")
                    public void onError(WebSocket webSocket, Throwable error) {
                        Log.error("WebSocket error: ");
                        error.printStackTrace();
                        WebSocket.Listener.super.onError(webSocket, error);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        Log.warning("WebSocket closed: Code=" +  statusCode + " Resason=" + reason);
                        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                    }
                }).join();

        for (String request : pendingRequest) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(COOLDOWN_MS));
            webSocket.sendText(request, true);
        }
        executor.execute(this::startLoopPing);
        startWebSocket = true;
        Log.info("<green>Connector Running: %s", this.getClass().getName());
    }

    @Override
    public void stop() {
        startWebSocket = false;
        Log.info("<green>Connector Stopped: %s", this.getClass().getName());
    }

    @Blocking
    protected void startLoopPing(){
        while (startWebSocket){
            sendPing();
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));
        }
    }

    protected @NotNull JsonNode sendSignedRequest(@NotNull Method method, String endpoint, TreeMap<String, String> params) throws ApiException {
        try {
            if (apiKey == null) {
                throw new NotSetApiKeysException("API Key not set");
            }
            String queryString = buildQueryString(params);
            String signature = hmacSha256(queryString, apiKey.secret);
            String finalUrl = getBaseURL(endpoint) + endpoint + "?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .header("X-MBX-APIKEY", apiKey.key)
                    .method(method.name(), HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = clientHttp.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode root = mapper.readTree(response.body());
            String symbolName = params.get("symbol");
            Symbol symbol = null;
            if (symbolName != null && endpoint.startsWith("/fapi")) {
                symbol = cachedSymbols.get(symbolName);
            }

            return root;
        }catch (Exception e) {
            throw new ApiException(e);
        }
    }

    protected @NotNull JsonNode sendPublicRequest(@NotNull Method method,
                                                @NotNull String endpoint,
                                                @NotNull Map<String, String> params) throws ApiException {
        try {
            String queryString = buildQueryString(params);
            String finalUrl = getBaseURL(endpoint) + (
                    queryString.isBlank()
                            ? endpoint
                            : endpoint + "?" + queryString
            );
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .method(method.toString(), HttpRequest.BodyPublishers.noBody())
                    .build();

            ObjectMapper mapper = new ObjectMapper();
            return mapper.readTree(clientHttp.send(request, HttpResponse.BodyHandlers.ofString()).body());
        } catch (Exception e) {
            throw new ApiException(e);
        }
    }

    protected String buildQueryString(@NotNull Map<String, String> params) {
        StringJoiner sj = new StringJoiner("&");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            sj.add(
                    URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                            + "="
                            + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)
            );
        }
        return sj.toString();
    }

    protected String hmacSha256(@NotNull String data, @NotNull String secret) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256_HMAC.init(secret_key);
        byte[] raw = sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(2 * raw.length);
        for (byte b : raw) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    protected @Nullable String accumulateMessage(
            @NotNull CharSequence data,
            boolean last,
            @NotNull Object lock,
            @NotNull StringBuilder buffer
    ) {
        synchronized (lock) {
            buffer.append(data);
            if (!last) {
                return null;
            }
            String content = buffer.toString();
            buffer.setLength(0);
            return content;
        }
    }

    protected Double fastParseDouble(@NotNull String s) {
        long integerPart = 0;
        long decimalPart = 0;
        long divisor = 1;

        boolean decimal = false;

        for (char c : s.toCharArray()) {

            if (c == '.') {
                decimal = true;
                continue;
            }

            int digit = c - '0';

            if (!decimal) {
                integerPart = integerPart * 10 + digit;
            } else {
                decimalPart = decimalPart * 10 + digit;
                divisor *= 10;
            }
        }

        return integerPart + (double) decimalPart / divisor;
    }

    protected boolean savePendingRequest(@NotNull String endpoint) {
        if (webSocket == null) {
            pendingRequest.add(endpoint);
            return true;
        }else {
            return false;
        }
    }

    protected abstract URL getURL();

    protected abstract void sendPing();

    protected abstract @NotNull String getBaseURL(@NotNull String baseURL);

    protected abstract void handleStreamMessage(@NotNull String contentToParse);

    public record URL(String baseHttps, String baseWws) {}

    protected enum Method {
        GET,
        POST,
        PUT,
        DELETE
    }
}
