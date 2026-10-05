# cloud-config-service

Spring Cloud Config Server with a composite backend: configuration files from `service-configs`, plus Vault for callers that present a Vault token.

Part of the identity platform; the platform root is [`../micro-services`](../micro-services/README.md). This repository must sit next to it, because the version catalog is read from there.

## Run

The usual way is the whole stack: `make up` in `../micro-services`. It then serves on port 9311:

```bash
curl localhost:9311/user-service/docker
```

From source, serving `../service-configs` directly: `./gradlew bootRun`.

## Test

```bash
./gradlew build
```

Needs Docker; the tests run against a real Vault container.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `native-repo` | `native-repo` reads files from a directory; `git-repo` reads `service-configs` from its Git remote |
| `CONFIG_REPO_LOCATION` | `file:../service-configs` | Directory for `native-repo` |
| `CONFIG_REPO_URI`, `CONFIG_REPO_LABEL` | none, `master` | Repository and branch for `git-repo` |
| `VAULT_HOST`, `VAULT_PORT` | `localhost`, `8200` | Vault |
| `EUREKA_ENABLED`, `EUREKA_URL` | `false`, `http://localhost:9111/eureka/` | Registration with Eureka |
| `SERVER_PORT` | `9311` | HTTP port |

A request without an `X-Config-Token` header gets the file-based configuration only. With a Vault token in that header, the response also contains that caller's secrets from `secret/<application>` and `secret/application`, as far as the token's policy allows. Services send their own token (`spring.cloud.config.token`).

It runs without authentication inside the dev network.
