# A2A Bedrock Adapter

Mule 4 application that exposes an **A2A (Agent-to-Agent)** HTTP endpoint and forwards `message/send` tasks to **Amazon Bedrock Agent Runtime** (`InvokeAgent`), then returns an A2A-shaped response the **mule4-a2a-connector** can record in task history.

## Prerequisites

- JDK 17+ and Maven
- Mule Runtime **4.10.x** (see `pom.xml`)
- An AWS **Bedrock agent** and **agent alias**
- An IAM **user access key** (**`AKIA…`**) with permission to **`bedrock:InvokeAgent`** (and any other actions your agent requires)

## Hit the ground running

### 1. Create local configuration (not committed)

`src/main/resources/config.properties` is **gitignored**. Create it from scratch:

```properties
# Listeners
http.listener.port=8082
a2a.listener.port=8081

# Public agent URL (use a host clients can reach in non-local deployments)
agent.host.name=localhost
agent.host.protocol=http
agent.host=${agent.host.protocol}://${agent.host.name}:${a2a.listener.port}
agent.path=/compliance-bedrock-agent

# Agent card (customize)
agent.name=Your Agent Name
agent.version=1.0.0
agent.description=Short description for the agent card.
agent.skill.1.id=1
agent.skill.1.name=Primary skill
agent.skill.1.description=Skill description.
agent.skill.1.tags=["bedrock","aws"]

# AWS — region where the agent alias exists
aws.region=us-east-2
aws.service.signing.name=bedrock

# IAM user (long-lived) — MUST be AKIA*, not ASIA*
aws.accessKey=YOUR_AKIA_ACCESS_KEY_ID
aws.secretKey=YOUR_SECRET_ACCESS_KEY

aws.agent.id=YOUR_AGENT_ID
aws.alias.id=YOUR_AGENT_ALIAS_ID
```

**Important:**

- **`aws.service.signing.name`** must stay **`bedrock`** for Agent Runtime.
- Do **not** use temporary **`ASIA*`** keys with this build; use a dedicated IAM user **`AKIA`** key pair.

### 2. Build

```bash
mvn clean package -DskipTests
```

Deploy the generated Mule deployable (per your Anypoint Studio / Runtime Manager process).

### 3. Call the A2A endpoint (local)

Use **`http`** (not `https`) unless you add TLS to the listener.

```bash
curl --location 'http://localhost:8081/compliance-bedrock-agent' \
  --header 'Content-Type: application/json' \
  --data '{
    "jsonrpc": "2.0",
    "id": "req-001",
    "method": "message/send",
    "params": {
      "message": {
        "kind": "message",
        "messageId": "550e8400-e29b-41d4-a716-446655440000",
        "role": "user",
        "parts": [{ "kind": "text", "text": "Your question here." }]
      }
    }
  }'
```

Adjust **`agent.path`** and port if you changed them in `config.properties`.

## Project layout (main pieces)

| Area | Role |
|------|------|
| `src/main/mule/a2a-bedrock-adapter.xml` | A2A listener, session handling, Java invoke, **root `kind: "message"`** response |
| `AwsSigV4HttpHeaders` | SigV4 signing for `POST` to Agent Runtime |
| `BedrockAgentInvoker` | JDK `HttpClient` call + restricted-header handling |
| `BedrockAgentStreamDecoder` | Binary Event Stream decode + chunk aggregation |
| `BedrockAgentInvokeOutcome` | Success text vs **-32603** style failure from Java |

## Troubleshooting

| Symptom | Likely cause |
|--------|----------------|
| TLS error on `https://localhost:8081` | Listener is plain **HTTP**; use **`http://`** locally. |
| `restricted header name: "Host"` | Fixed in repo: invoker skips `Host` / `Content-Length` for `HttpClient`. |
| `Response was neither of kind 'task' nor 'message'` | Flow must return **root** **`kind`**, not only JSON-RPC. |
| `missing arg0…` on Java invoke | Use **`arg0`…`argN`** in Mule, in **method parameter order**. |
| Empty model text | Event stream not decoded; ensure you are on a build with **`BedrockAgentStreamDecoder`**. |

## Further reading

See **[`docs/technical-learnings.md`](docs/technical-learnings.md)** for deeper notes (SigV4 scope, event stream, A2A connector expectations).
