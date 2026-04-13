package com.mulesoft.a2a.bedrock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Amazon Bedrock Agent Runtime {@code InvokeAgent} {@code application/vnd.amazon.eventstream} bodies:
 * reads prelude + headers + payload per message, inspects {@code :event-type}, decodes {@code chunk} {@code bytes}
 * (base64), and detects error events (e.g. {@code accessDeniedException}, {@code dependencyFailedException}).
 */
public final class BedrockAgentStreamDecoder {

    public static final class DecodeResult {
        private final String text;
        private final String streamErrorType;
        private final String streamErrorDetail;

        public DecodeResult(String text, String streamErrorType, String streamErrorDetail) {
            this.text = text == null ? "" : text;
            this.streamErrorType = streamErrorType;
            this.streamErrorDetail = streamErrorDetail;
        }

        public String getText() {
            return text;
        }

        public boolean hasStreamError() {
            return streamErrorType != null;
        }

        public String getStreamErrorType() {
            return streamErrorType;
        }

        public String getStreamErrorDetail() {
            return streamErrorDetail;
        }
    }

    private static final int PRELUDE_LEN = 12;
    private static final int TRAILER_LEN = 4;

    private static final int HDR_TYPE_BOOL_TRUE = 0;
    private static final int HDR_TYPE_BOOL_FALSE = 1;
    private static final int HDR_TYPE_BYTE = 2;
    private static final int HDR_TYPE_SHORT = 3;
    private static final int HDR_TYPE_INT = 4;
    private static final int HDR_TYPE_LONG = 5;
    private static final int HDR_TYPE_BYTES = 6;
    private static final int HDR_TYPE_STRING = 7;
    private static final int HDR_TYPE_TIMESTAMP = 8;
    private static final int HDR_TYPE_UUID = 9;

    private static final Pattern BYTES_FIELD = Pattern.compile("\"bytes\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern MESSAGE_FIELD = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private BedrockAgentStreamDecoder() {}

    public static DecodeResult decodeEventStream(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return new DecodeResult("", null, null);
        }
        List<String> chunks = new ArrayList<>();
        String firstErrorType = null;
        String firstErrorDetail = null;
        int pos = 0;
        while (pos + PRELUDE_LEN + TRAILER_LEN <= raw.length) {
            int totalLen = readUInt32Be(raw, pos);
            int headersLen = readUInt32Be(raw, pos + 4);
            if (totalLen < PRELUDE_LEN + TRAILER_LEN
                    || headersLen > totalLen - PRELUDE_LEN - TRAILER_LEN
                    || pos + totalLen > raw.length) {
                break;
            }
            int payloadLen = totalLen - PRELUDE_LEN - headersLen - TRAILER_LEN;
            int headersStart = pos + PRELUDE_LEN;
            int payloadStart = headersStart + headersLen;
            String eventType = parseEventTypeHeader(raw, headersStart, headersLen);
            if (payloadLen > 0 && payloadStart + payloadLen <= raw.length) {
                String payloadJson = new String(raw, payloadStart, payloadLen, StandardCharsets.UTF_8);
                if (eventType != null && isErrorEventType(eventType)) {
                    if (firstErrorType == null) {
                        firstErrorType = eventType;
                        firstErrorDetail = extractErrorMessage(payloadJson);
                    }
                } else if (eventType == null || "chunk".equalsIgnoreCase(eventType) || payloadJson.contains("\"bytes\"")) {
                    Matcher m = BYTES_FIELD.matcher(payloadJson);
                    while (m.find()) {
                        String b64 = m.group(1);
                        if (!b64.isEmpty()) {
                            byte[] decoded = Base64.getDecoder().decode(b64);
                            chunks.add(new String(decoded, StandardCharsets.UTF_8));
                        }
                    }
                }
            }
            pos += totalLen;
        }
        if (chunks.isEmpty() && firstErrorType == null && looksLikeUtf8JsonWithBytes(raw)) {
            String s = new String(raw, StandardCharsets.UTF_8);
            Matcher m = BYTES_FIELD.matcher(s);
            while (m.find()) {
                String b64 = m.group(1);
                if (!b64.isEmpty()) {
                    byte[] decoded = Base64.getDecoder().decode(b64);
                    chunks.add(new String(decoded, StandardCharsets.UTF_8));
                }
            }
        }
        String text = String.join("", chunks);
        return new DecodeResult(text, firstErrorType, firstErrorDetail);
    }

    private static boolean isErrorEventType(String eventType) {
        String t = eventType.toLowerCase(Locale.ROOT);
        return t.endsWith("exception");
    }

    private static String extractErrorMessage(String payloadJson) {
        Matcher m = MESSAGE_FIELD.matcher(payloadJson);
        if (m.find()) {
            return unescapeJsonString(m.group(1));
        }
        return truncate(payloadJson, 1500);
    }

    private static String unescapeJsonString(String s) {
        return s.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }

    /**
     * AWS Event Stream headers: repeating (u8 nameLen, name utf8, u8 type, value...).
     */
    private static String parseEventTypeHeader(byte[] buf, int start, int length) {
        int p = start;
        int end = start + length;
        while (p < end) {
            int nameLen = buf[p++] & 0xff;
            if (p + nameLen > end) {
                break;
            }
            String name = new String(buf, p, nameLen, StandardCharsets.UTF_8);
            p += nameLen;
            if (p >= end) {
                break;
            }
            int type = buf[p++] & 0xff;
            if (":event-type".equals(name) && type == HDR_TYPE_STRING) {
                if (p + 2 > end) {
                    break;
                }
                int vlen = ((buf[p] & 0xff) << 8) | (buf[p + 1] & 0xff);
                if (p + 2 + vlen > end) {
                    break;
                }
                return new String(buf, p + 2, vlen, StandardCharsets.UTF_8);
            }
            int next = skipHeaderValue(buf, p, end, type);
            if (next < 0) {
                break;
            }
            p = next;
        }
        return null;
    }

    private static int skipHeaderValue(byte[] buf, int p, int end, int type) {
        switch (type) {
            case HDR_TYPE_BOOL_TRUE:
            case HDR_TYPE_BOOL_FALSE:
                return p;
            case HDR_TYPE_BYTE:
                return p + 1 <= end ? p + 1 : -1;
            case HDR_TYPE_SHORT:
                return p + 2 <= end ? p + 2 : -1;
            case HDR_TYPE_INT:
                return p + 4 <= end ? p + 4 : -1;
            case HDR_TYPE_LONG:
            case HDR_TYPE_TIMESTAMP:
                return p + 8 <= end ? p + 8 : -1;
            case HDR_TYPE_BYTES:
            case HDR_TYPE_STRING:
                if (p + 2 > end) {
                    return -1;
                }
                int slen = ((buf[p] & 0xff) << 8) | (buf[p + 1] & 0xff);
                return p + 2 + slen <= end ? p + 2 + slen : -1;
            case HDR_TYPE_UUID:
                return p + 16 <= end ? p + 16 : -1;
            default:
                return -1;
        }
    }

    private static boolean looksLikeUtf8JsonWithBytes(byte[] raw) {
        if (raw.length > 2000) {
            return false;
        }
        String s = new String(raw, StandardCharsets.UTF_8);
        return s.contains("\"bytes\"");
    }

    private static int readUInt32Be(byte[] b, int off) {
        return ((b[off] & 0xff) << 24)
                | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8)
                | (b[off + 3] & 0xff);
    }
}
