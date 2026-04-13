package com.mulesoft.a2a.bedrock;

/**
 * Result of a Bedrock Agent invoke: either aggregated assistant text or a JSON-RPC style error.
 */
public final class BedrockAgentInvokeOutcome {

    /** JSON-RPC internal error (e.g. Bedrock stream faults). */
    public static final int JSON_RPC_INTERNAL_ERROR = -32603;

    private final boolean ok;
    private final String aggregatedText;
    private final Integer errorCode;
    private final String errorMessage;

    private BedrockAgentInvokeOutcome(boolean ok, String aggregatedText, Integer errorCode, String errorMessage) {
        this.ok = ok;
        this.aggregatedText = aggregatedText;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public static BedrockAgentInvokeOutcome success(String text) {
        return new BedrockAgentInvokeOutcome(true, text == null ? "" : text, null, null);
    }

    public static BedrockAgentInvokeOutcome failure(int jsonRpcCode, String message) {
        return new BedrockAgentInvokeOutcome(false, "", jsonRpcCode, message == null ? "Unknown error" : message);
    }

    public boolean isOk() {
        return ok;
    }

    public String getAggregatedText() {
        return aggregatedText;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
