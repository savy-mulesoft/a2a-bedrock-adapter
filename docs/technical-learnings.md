# Technical learnings: A2A ↔ Bedrock Agent Runtime

Design notes and pitfalls from wiring **Mule 4 A2A** to **Amazon Bedrock Agent Runtime** (`InvokeAgent`). This reflects the **current** codebase, not every experiment along the way.

---

## Table of contents

1. [SigV4 and the correct service name](#1-sigv4-and-the-correct-service-name)
2. [Long-lived IAM user keys (AKIA)](#2-long-lived-iam-user-keys-akia)
3. [`HttpClient` and the `Host` header](#3-httpclient-and-the-host-header)
4. [Binary Event Stream responses](#4-binary-event-stream-responses)
5. [Mule `java:invoke-static` arguments](#5-mule-javainvoke-static-arguments)
6. [A2A connector and root `kind`](#6-a2a-connector-and-root-kind)
7. [Region vs Mule worker](#7-region-vs-mule-worker)
8. [IAM and marketplace](#8-iam-and-marketplace)

---

## 1. SigV4 and the correct service name

| Item | Value |
|------|--------|
| **API host** | `bedrock-agent-runtime.<region>.amazonaws.com` |
| **Property** | `aws.service.signing.name` |
| **Must be** | `bedrock` |

Using a signing name scoped like `bedrock-agent-runtime` (or similar) can produce **credential scope** errors from AWS. Postman’s “Service Name” for this API aligns with **`bedrock`**.

---

## 2. Long-lived IAM user keys (AKIA)

- [`AwsSigV4HttpHeaders`](../src/main/java/com/mulesoft/a2a/bedrock/AwsSigV4HttpHeaders.java) targets **IAM user access keys** whose access key id starts with **`AKIA`**.
- **Temporary `ASIA*` credentials** require a session token and expire; the signer **rejects** `ASIA*` on purpose so misconfiguration fails fast.
- There is **no** `aws.sessionToken` property in this design.

---

## 3. `HttpClient` and the `Host` header

AWS SigV4 includes a **`Host`** header on the canonical request.

`java.net.http.HttpRequest.Builder` **does not allow** setting `Host` manually (`IllegalArgumentException: restricted header name: "Host"`).

[`BedrockAgentInvoker`](../src/main/java/com/mulesoft/a2a/bedrock/BedrockAgentInvoker.java) omits **restricted** headers (`Host`, `Content-Length`, etc.) when building the request. The client infers them from the **URI** and **body**, which matches what was signed.

---

## 4. Binary Event Stream responses

`InvokeAgent` returns **`application/vnd.amazon.eventstream`**, not a single JSON document.

- Casting the body to **string** and regex-matching `"bytes"` is **unreliable** on real binary frames.
- [`BedrockAgentStreamDecoder`](../src/main/java/com/mulesoft/a2a/bedrock/BedrockAgentStreamDecoder.java) parses **AWS Event Stream** messages (prelude, headers, payload), reads **`:event-type`**, and for **`chunk`** events decodes JSON **`bytes`** (Base64) into UTF-8 and **concatenates** the assistant text.
- Payload events whose `:event-type` ends with **`Exception`** are treated as stream errors and surfaced via [`BedrockAgentInvokeOutcome`](../src/main/java/com/mulesoft/a2a/bedrock/BedrockAgentInvokeOutcome.java) (e.g. JSON-RPC **`-32603`** semantics at the Java layer).

---

## 5. Mule `java:invoke-static` arguments

The Java module expects **`arg0`, `arg1`, …** in **declaration order**, not Java parameter names (`region`, `accessKey`, …).

If you use names like `region:` instead of `arg0:`, Mule may report missing **`arg0`…`argN`**.

---

## 6. A2A connector and root `kind`

`SendMessageExecutor` (task history) expects the **flow output** root object to include:

```json
"kind": "message"
```

or `"kind": "task"`.

If the flow returns only a **JSON-RPC envelope** (`jsonrpc`, `id`, `result`) **without** a root `kind`, you get:

> Response was neither of kind 'task' nor 'message'

The flow should return the **A2A result shape** with **root** `kind: "message"` (and nested `message` as required). The connector still handles JSON-RPC for HTTP clients where applicable.

---

## 7. Region vs Mule worker

- **`aws.region`** = region where the **Bedrock agent alias** lives (same as the Runtime API host).
- The **Mule worker** may run in another region; IAM must still allow **`bedrock:InvokeAgent`** (and related actions) for the agent resource/account.

---

## 8. IAM and marketplace

Some models or marketplace flows need extra permissions (e.g. subscription checks). A **custom IAM policy** on the principal behind **`AKIA`** with at least **`bedrock:InvokeAgent`** (and whatever your agent uses) is typical.

---

## Historical note

An earlier approach used **`assumeRole`** and temporary **`ASIA*`** bootstrap keys in `config.properties`. The **shipped** adapter uses **AKIA + `BedrockAgentInvoker` + stream decoding** as described above.
