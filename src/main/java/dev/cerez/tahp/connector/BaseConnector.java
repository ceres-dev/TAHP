package dev.cerez.tahp.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cerez.tahp.Log;
import dev.cerez.tahp.connector.connectors.exception.PostOnlyRejectException;
import dev.cerez.tahp.connector.exception.ApiException;
import dev.cerez.tahp.connector.exception.NotSetApiKeysException;
import dev.cerez.tahp.connector.model.BookTickDouble;
import dev.cerez.tahp.connector.model.Symbol;
import dev.cerez.tahp.io.IOdata;
import dev.cerez.tahp.utils.Telemetry;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
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
import java.util.function.Consumer;

public abstract class BaseConnector implements Connector {

    protected static final int MAX_STREAMS_PER_SUBSCRIBE = 100;
    protected static final int TIMEOUT = 30;
    protected static final int COOLDOWN_MS = 1_000;

    @NotNull  protected final ObjectMapper mapper = new ObjectMapper();
    @NotNull  protected final HttpClient clientHttp = HttpClient.newHttpClient();
    @NotNull  protected final HashMap<String, Symbol> cachedSymbols = new HashMap<>();
    @NotNull  protected final ExecutorService executor = Executors.newFixedThreadPool(8);
    @NotNull  protected final Map<String, Set<String>> pendingRequest = new HashMap<>();
    @NotNull  protected final Map<String, WebSocket> webSockets = new HashMap<>();
    @NotNull  protected final Map<String, Boolean> isStartWebSockets = new HashMap<>();

              protected final boolean isTestNet;
    @Nullable protected       Keys apiKey;
    @Setter   protected       Telemetry telemetry;

    @NotNull  private final Object streamIncomingLock = new Object();
    @NotNull  private final StringBuilder streamIncomingMessage = new StringBuilder();
    @NotNull  protected final HashMap<String, Consumer<JsonNode>> consumerStreamsMap = new HashMap<>();

    protected volatile boolean waitingForPong = false;
    protected volatile long delayPingPongNanoTime = -1;
    protected volatile long deltaClienteToServer = 0;
    protected volatile boolean runLoopers = false;

    @Getter
    @Setter
    protected boolean logEndpoint = false;
    @Setter
    protected Consumer<BookTickDouble> consumerBookTicker;

    public BaseConnector() {
        this.isTestNet = false;
    }

    public BaseConnector(boolean isTestNet) {
        this.isTestNet = isTestNet;
    }

    public void invalidateCache() {
        cachedSymbols.clear();
    }

    @Override
    public void subscribeBookTicker(@NotNull Collection<String> symbols) {
        List<String> streams = new ArrayList<>(symbols);
        for (int i = 0; i < streams.size(); i += MAX_STREAMS_PER_SUBSCRIBE) {
            int end = Math.min(i + MAX_STREAMS_PER_SUBSCRIBE, streams.size());
            subscribeBookTickerBatch(streams.subList(i, end));
            if (webSockets.get(getWWS()) != null) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(COOLDOWN_MS));
        }
    }

    @Override
    public void start(){
        loadApikey();
        initWebSocket(getWWS());
        runLoopers();
    }

    @Override
    public void stop() {
        for (Map.Entry<String, Boolean> entry : isStartWebSockets.entrySet()) {
            entry.setValue(false);
        }
        for (Map.Entry<String, WebSocket> entry : webSockets.entrySet()) {
            entry.getValue().sendClose(0, "The program has ended.");
        }
        isStartWebSockets.clear();
        webSockets.clear();
        pendingRequest.clear();
        stopLoopers();
    }

    public void loadApikey(){
        apiKey = IOdata.loadApiKeysBinance();
    }

    public void runLoopers(){
        runLoopers = true;
        executor.execute(() -> {
            while (runLoopers) {
                deltaClienteToServer = getTimeSever() - System.currentTimeMillis();
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(60));
            }
        });
        executor.execute(() -> {
            while (runLoopers) {
                LockSupport.parkNanos(TimeUnit.MINUTES.toNanos(30));
                invalidateCache();
            }
        });
    }

    public void stopLoopers(){
        this.runLoopers = false;
    }

    public void initWebSocket(String wwsURL) {
        if (webSockets.containsKey(wwsURL) && isStartWebSockets.get(wwsURL)) return;
        webSockets.put(wwsURL, clientHttp.newWebSocketBuilder()
                .buildAsync(URI.create(wwsURL), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        Log.error("WebSocket@%s open", wwsURL);
                        webSocket.request(1);
                        WebSocket.Listener.super.onOpen(webSocket);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        String contentToParse = accumulateMessage(data, last, streamIncomingLock, streamIncomingMessage);
                        if (contentToParse != null) {
                            handleStreamRaw(wwsURL, contentToParse);
                        }
                        webSocket.request(1);
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }

                    @Override
                    @SuppressWarnings("CallToPrintStackTrace")
                    public void onError(WebSocket webSocket, Throwable error) {
                        Log.error("WebSocket@%s error: ".formatted(wwsURL));
                        error.printStackTrace();
                        WebSocket.Listener.super.onError(webSocket, error);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        Log.warning("WebSocket@%s closed: Code=%d Reason=%s".formatted(wwsURL, statusCode, reason));
                        isStartWebSockets.put(wwsURL, false);
                        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                    }
                }).join());
        isStartWebSockets.put(wwsURL, true);
        for (String request : pendingRequest.getOrDefault(wwsURL, Collections.emptySet())) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(COOLDOWN_MS));
            sendWebSocket(request);
        }
        executor.execute(() -> {
            while (isStartWebSockets.containsKey(wwsURL) && isStartWebSockets.get(wwsURL)) {
                String ping = getPingPayload(wwsURL);
                if (ping == null){
                    return;
                }
                if (webSockets.containsKey(wwsURL)) {
                    sendWebSocket(ping);
                    waitingForPong = true;
                    delayPingPongNanoTime = System.nanoTime();
                }
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));
            }
        });
    }

    public void sendWebSocket(String content) {
        sendWebSocket(getWWS(), content);
    }

    public void sendWebSocket(String wwsURL, String content) {
        if (savePendingRequest(wwsURL, content)) return;
        if (logEndpoint) Log.info("wws=%s@%s", wwsURL, content);
        webSockets.get(wwsURL).sendText(content.replaceAll("\\s", ""), true);
    }

    protected @NotNull JsonNode sendSignedRequest(@NotNull Method method,
                                                  @NotNull String endpoint
    ) {
        return sendSignedRequest(getHTTPS(), method, endpoint, new HashMap<>());
    }

    protected @NotNull JsonNode sendSignedRequest(@NotNull Method method,
                                                  @NotNull String endpoint,
                                                  @NotNull Map<String, Object> params
    ) {
        return sendSignedRequest(getHTTPS(), method, endpoint, params);
    }

    protected @NotNull JsonNode sendSignedRequest(@NotNull String baseUrl,
                                                  @NotNull Method method,
                                                  @NotNull String endpoint
    ) {
        return sendSignedRequest(baseUrl, method, endpoint, new HashMap<>());
    }

    protected @NotNull JsonNode sendSignedRequest(@NotNull String baseUrl,
                                                  @NotNull Method method,
                                                  @NotNull String endpoint,
                                                  @NotNull Map<String, Object> params
    ) {
        if (apiKey == null) {
            throw new NotSetApiKeysException("API Key not set");
        }
        params.put("timestamp", System.currentTimeMillis() + deltaClienteToServer);
        String queryString = buildQueryString(params);
        try {
            String signature = hmacSha256(queryString, apiKey.secret);
            String finalUrl = baseUrl + endpoint + "?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .header("X-MBX-APIKEY", apiKey.key)
                    .method(method.name(), HttpRequest.BodyPublishers.noBody())
                    .build();
            if (logEndpoint && !getBlackListEndpointLog().contains(endpoint)) Log.info("https=%s %s", method, finalUrl);
            JsonNode jsonRaw = null;
            try {
                HttpResponse<String> response = clientHttp.send(request, HttpResponse.BodyHandlers.ofString());
                checkResponse(jsonRaw = mapper.readTree(response.body()), request);
                return jsonRaw;
            } catch (IOException | InterruptedException e) {
                Log.error("Error de IO o Interrupción: %s", e.getMessage());
                throw new RuntimeException(e);
            }catch (ApiException e) {
                Log.error("%s %s -> %s", method.name(), finalUrl, jsonRaw);
                throw e;
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }

    }

    @SuppressWarnings("SameParameterValue")
    protected @NotNull JsonNode sendPublicRequest(@NotNull Method method,
                                                  @NotNull String endpoint
    ) {
        return sendPublicRequest(getHTTPS(), method, endpoint, new HashMap<>());
    }

    @SuppressWarnings("SameParameterValue")
    protected @NotNull JsonNode sendPublicRequest(@NotNull Method method,
                                                  @NotNull String endpoint,
                                                  @NotNull Map<String, Object> params
    ) {
        return sendPublicRequest(getHTTPS(), method, endpoint, params);
    }

    @SuppressWarnings("SameParameterValue")
    protected @NotNull JsonNode sendPublicRequest(@NotNull String baseUrl,
                                                  @NotNull Method method,
                                                  @NotNull String endpoint
    ) {
        return sendPublicRequest(baseUrl, method, endpoint, new HashMap<>());
    }

    protected @NotNull JsonNode sendPublicRequest(@NotNull String baseUrl,
                                                  @NotNull Method method,
                                                  @NotNull String endpoint,
                                                  @NotNull Map<String, Object> params
    ) {
        String queryString = buildQueryString(params);
        String finalUrl = baseUrl + (
                queryString.isBlank()
                        ? endpoint
                        : endpoint + "?" + queryString
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(finalUrl))
                .method(method.toString(), HttpRequest.BodyPublishers.noBody())
                .build();
        if (logEndpoint && !getBlackListEndpointLog().contains(endpoint)) Log.info("https=%s %s", method, finalUrl);
        String jsonRaw = null;
        if (telemetry != null) telemetry.addRequestConnector(method, finalUrl);
        try {
            jsonRaw = clientHttp.send(request, HttpResponse.BodyHandlers.ofString()).body();
            JsonNode node = mapper.readTree(jsonRaw);
            checkResponse(node, request);
            return node;
        } catch (IOException | InterruptedException | ApiException e) {
            Log.error(finalUrl + " @ " + jsonRaw);
            throw new RuntimeException(e);
        }
    }

    protected String buildQueryString(@NotNull Map<String, Object> params) {
        StringJoiner sj = new StringJoiner("&");
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            sj.add(
                    URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                            + "="
                            + URLEncoder.encode(entry.getValue().toString(), StandardCharsets.UTF_8)
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

    @Contract(pure = true)
    protected static @NotNull Double fastParseDouble(@NotNull String s) {
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

    protected boolean savePendingRequest(@NotNull String wwsURL, @NotNull String content) {
        if (webSockets.containsKey(wwsURL) && isStartWebSockets.getOrDefault(wwsURL, false)) {
            return false;
        } else {
            pendingRequest.computeIfAbsent(wwsURL, (k) -> new HashSet<>()).add(content);
            return true;
        }
    }

    protected void checkResponse(@NotNull JsonNode response, @NotNull HttpRequest request) throws ApiException {
        if (response.has("code")) {
            int code = response.get("code").asInt();
            if (code != 200) throw new ApiException("Error: Code=%d Message=%s Request=%s Method=%s".formatted(code, response.get("msg").asText(), request.uri().toString(), request.method()), request);
        }
    }

    protected void removeConsumerStreams(@NotNull String key) {
        consumerStreamsMap.remove(key);
    }

    protected void addConsumerStreams(@NotNull String key, @NotNull Consumer<JsonNode> consumer, boolean muliThreading) {
        if (muliThreading) {
            consumerStreamsMap.put(key, (json) -> executor.execute(() -> consumer.accept(json)));
        }else {
            consumerStreamsMap.put(key, consumer);
        }
    }

    protected abstract void handleStreamRaw(@NotNull String wwsURL, @NotNull String contentToParse);

    protected abstract void subscribeBookTickerBatch(@NotNull List<String> symbols);

    protected abstract @Nullable String getPingPayload(@NotNull String wwsURL);

    protected abstract @NotNull Set<String> getBlackListEndpointLog();

    public abstract @NotNull String getHTTPS();

    public abstract @NotNull String getWWS();

    public enum Method {
        GET,
        POST,
        PUT,
        DELETE
    }

    @Data
    public abstract static class Keys{
        @NotNull private final String key;
        @NotNull private final String secret;
    }
}
