# cloud-config-service

Spring Cloud Config Server with a composite backend: configuration files from `service-configs`, plus Vault for callers that present a Vault token.

Part of the identity platform; the platform root is [`../micro-services`](../micro-services/README.md). This repository must sit next to it, because the version catalog is read from there.

## Run

The usual way is the whole stack: `make up` in `../micro-services`. It then serves on port 9311:

```bash
curl localhost:9311/user-service/dev
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
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev` reads files from a directory; `qa` and `prod` read `service-configs` from Git |
| `CONFIG_REPO_LOCATION` | `file:../service-configs` | The directory, in `dev` |
| `CONFIG_REPO_URI`, `CONFIG_REPO_LABEL` | none, `main` | Repository and branch or tag, in `qa` and `prod` |
| `CONFIG_REPO_TYPE` | `git` | Set to `native` to make `qa` or `prod` read `CONFIG_REPO_LOCATION` instead, for a local run |
| `VAULT_HOST`, `VAULT_PORT` | `localhost` in `dev`, required otherwise; `8200` | Vault |
| `EUREKA_ENABLED`, `EUREKA_URL` | `false` and `http://localhost:9111/eureka/` in `dev`; on and required otherwise | Registration with Eureka |
| `SERVER_PORT` | `9311` | HTTP port |

Its own configuration is split the same way as everyone else's: `application.yml` plus `application-dev.yml`, `-qa.yml` and `-prod.yml`.

A request without an `X-Config-Token` header gets the file-based configuration only. With a Vault token in that header, the response also contains that caller's secrets from `secret/<application>` and `secret/application`, as far as the token's policy allows. Services send their own token (`spring.cloud.config.token`).

It runs without authentication inside the dev network.
