package com.mulesoft.a2a.bedrock;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Produces SigV4-signed headers for HTTPS calls to Amazon Bedrock Agent Runtime.
 * <p>
 * Expects a long-lived IAM user access key ({@code AKIA*}). Temporary {@code ASIA*} keys are rejected.
 */
public final class AwsSigV4HttpHeaders {

    private AwsSigV4HttpHeaders() {}

    public static Map<String, String> signPost(
            String region,
            String serviceSigningName,
            String accessKey,
            String secretKey,
            String path,
            String bodyJson) {

        if (accessKey == null || accessKey.isEmpty() || secretKey == null || secretKey.isEmpty()) {
            throw new IllegalArgumentException("aws.accessKey and aws.secretKey must be non-empty");
        }
        if (accessKey.startsWith("ASIA")) {
            throw new IllegalArgumentException(
                    "Use a long-lived IAM user access key (AKIA*), not a temporary ASIA* key.");
        }

        AwsCredentials creds = AwsBasicCredentials.create(accessKey, secretKey);

        Aws4SignerParams params = Aws4SignerParams.builder()
                .awsCredentials(creds)
                .signingName(serviceSigningName)
                .signingRegion(Region.of(region))
                .build();

        String host = "bedrock-agent-runtime." + region + ".amazonaws.com";
        byte[] bodyBytes = bodyJson.getBytes(StandardCharsets.UTF_8);
        ContentStreamProvider bodyProvider = () -> new ByteArrayInputStream(bodyBytes);

        SdkHttpFullRequest unsigned = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST)
                .protocol("https")
                .host(host)
                .encodedPath(path)
                .putHeader("Content-Type", "application/json")
                .putHeader("Accept", "application/json")
                .contentStreamProvider(bodyProvider)
                .build();

        SdkHttpFullRequest signed = Aws4Signer.create().sign(unsigned, params);

        Map<String, String> headers = new HashMap<>();
        signed.headers().forEach((name, values) -> {
            if (values != null && !values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        return headers;
    }
}
