package com.mulesoft.a2a.bedrock;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Invokes Bedrock Agent Runtime {@code .../sessions/{sessionId}/text} with SigV4 headers, decodes the event stream,
 * and returns a {@link BedrockAgentInvokeOutcome} suitable for JSON-RPC (including {@code -32603} on faults).
 */
public final class BedrockAgentInvoker {

    /**
     * {@link HttpRequest.Builder#header(String, String)} rejects names the client controls; the URI still
     * sends the same {@code Host} as was used in SigV4.
     */
    private static final Set<String> HTTP_CLIENT_RESTRICTED_HEADERS = Set.of(
            "connection",
            "content-length",
            "expect",
            "host",
            "upgrade",
            "via",
            "warning");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private BedrockAgentInvoker() {}

    public static BedrockAgentInvokeOutcome invokeAgent(
            String region,
            String accessKey,
            String secretKey,
            String agentId,
            String aliasId,
            String sessionId,
            String inputText) {

        String path = "/agents/" + agentId + "/agentAliases/" + aliasId + "/sessions/" + sessionId + "/text";
        String bodyJson = "{\"inputText\":" + escapeJson(inputText) + "}";
        Map<String, String> headers = AwsSigV4HttpHeaders.signPost(region, "bedrock", accessKey, secretKey, path, bodyJson);

        String uri = "https://bedrock-agent-runtime." + region + ".amazonaws.com" + path;
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, java.nio.charset.StandardCharsets.UTF_8));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String name = e.getKey();
            if (name != null && HTTP_CLIENT_RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            rb.header(e.getKey(), e.getValue());
        }
        HttpRequest req = rb.build();
        try {
            HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                String errBody = new String(res.body(), java.nio.charset.StandardCharsets.UTF_8);
                return BedrockAgentInvokeOutcome.failure(
                        BedrockAgentInvokeOutcome.JSON_RPC_INTERNAL_ERROR,
                        "Bedrock Agent HTTP " + res.statusCode() + ": " + truncate(errBody, 2000));
            }
            BedrockAgentStreamDecoder.DecodeResult decoded = BedrockAgentStreamDecoder.decodeEventStream(res.body());
            if (decoded.hasStreamError()) {
                String detail = decoded.getStreamErrorDetail() == null ? "" : decoded.getStreamErrorDetail();
                String msg = decoded.getStreamErrorType() + ": " + detail;
                return BedrockAgentInvokeOutcome.failure(BedrockAgentInvokeOutcome.JSON_RPC_INTERNAL_ERROR, msg.trim());
            }
            return BedrockAgentInvokeOutcome.success(decoded.getText());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BedrockAgentInvokeOutcome.failure(
                    BedrockAgentInvokeOutcome.JSON_RPC_INTERNAL_ERROR,
                    "Bedrock Agent request interrupted");
        } catch (IOException e) {
            return BedrockAgentInvokeOutcome.failure(
                    BedrockAgentInvokeOutcome.JSON_RPC_INTERNAL_ERROR,
                    "Bedrock Agent request failed: " + e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\b') {
                sb.append("\\b");
            } else if (c == '\f') {
                sb.append("\\f");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }
}
