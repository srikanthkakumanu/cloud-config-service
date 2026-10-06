# cloud-config-service

The Config Server of the identity platform (Spring Cloud Config). Services ask it for their configuration at startup. It answers from two sources combined:

1. **Files** from the [`service-configs`](../service-configs/README.md) repository.
2. **Vault**, for a caller that presents its own Vault token.

It holds no configuration of the services itself and no secrets.

## How a request is answered

A service asks for `/{application}/{profile}`, for example `/user-service/dev`. The answer lists property sources, most specific first:

```bash
curl -s localhost:9311/user-service/dev | jq '[.propertySources[].name]'
# ["file:/config-repo/user-service-dev.yml", "file:/config-repo/application-dev.yml",
#  "file:/config-repo/user-service.yml",     "file:/config-repo/application.yml"]
```

So the environment file wins over the service file, which wins over the shared file.

**Vault:** if the request carries an `X-Config-Token` header with a Vault token, the answer also contains what that token may read from `secret/{application}` and `secret/application`. Without the header, only the files are served. Services send their own token (`spring.cloud.config.token`), so one service can never obtain another's secrets through the Config Server. Services also read Vault directly; the two agree.

Placeholders such as `${DATABASE_URL}` are returned as written and resolved by the service that receives them.

## File source per environment

| Profile | Source | Set with |
| --- | --- | --- |
| `dev` | A directory, read on every request, so an edit is served immediately | `CONFIG_REPO_LOCATION` |
| `qa`, `prod` | The `service-configs` Git repository at a branch or tag | `CONFIG_REPO_URI`, `CONFIG_REPO_LABEL` |

For a local run of `qa` or `prod` without a Git remote, `CONFIG_REPO_TYPE=native` makes them read `CONFIG_REPO_LOCATION` instead. The local Compose stack does this.

## Endpoints

| Path | What |
| --- | --- |
| `/{application}/{profile}` | Configuration as JSON property sources |
| `/{application}-{profile}.yml` | The merged result as YAML |
| `/actuator/health`, `/actuator/health/readiness` | Health |

Port: 9311. Application name: `cloud-config-service`.

## Configuration

Split by environment ([ADR 0014](../micro-services/docs/adr/0014-environment-profiles.md)):

| File | Holds |
| --- | --- |
| `application.yml` | Name, port, graceful shutdown, health; every environment is a composite of Vault and files |
| `application-dev.yml` | Vault and the directory source with localhost defaults; Eureka registration optional |
| `application-qa.yml`, `application-prod.yml` | Vault required; Git source; Eureka registration on and required |

| Variable | Default in `dev` | Meaning |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev`, `qa` or `prod` |
| `SERVER_PORT` | `9311` | HTTP port |
| `CONFIG_REPO_LOCATION` | `file:../service-configs` | The directory |
| `CONFIG_REPO_URI`, `CONFIG_REPO_LABEL` | none, `main` | Git repository and branch or tag (`qa`, `prod`) |
| `CONFIG_REPO_TYPE` | `git` in `qa` and `prod` | `native` to read the directory instead |
| `VAULT_HOST`, `VAULT_PORT` | `localhost`, `8200` | Vault; the host is required in `qa` and `prod` |
| `EUREKA_ENABLED`, `EUREKA_URL` | `false`, `http://localhost:9111/eureka/` | Registration with Eureka |

Notes:

- A caller without a Vault token still gets the files (`fail-on-composite-error: false`).
- It runs without authentication inside the platform network.
- Do not name a variable `VAULT_PORT` after a Kubernetes service: Kubernetes injects `VAULT_PORT=tcp://...` for a service called `vault`. The manifests turn service links off for this reason.

## Run

This repository must sit next to [`micro-services`](../micro-services/README.md), which holds the version catalog, and next to `service-configs`.

**With the whole platform** (the usual way): `cd ../micro-services && make up`. It starts in stage 5 of `scripts/start.sh`, after Vault has been seeded and Keycloak set up, and before the services, which cannot start without it. In Compose `service-configs` is mounted read-only at `/config-repo`.

**On its own, from source:** `./gradlew bootRun`. It serves `../service-configs` directly.

**Restart it:** `cd ../micro-services && scripts/restart.sh config-server` (the Compose service is called `config-server`). Running services keep the configuration they already loaded.

## Test

```bash
./gradlew build
```

21 tests, none skipped. Needs Docker: they run against a real Vault container. One class uses sample files; the other serves the real `../service-configs` and checks what each service (`user-service`, `auth-service`, `api-gateway`, `books-service`, `video-service`) receives in `dev`, `qa` and `prod`.

- environment file overrides service file overrides shared file
- the default profile gets the service file
- a caller with a Vault token gets its secrets; a caller without one gets none
- another service's secrets are never mixed in
- readiness
- every service has a file per environment and receives its own client ID and audience
- books-service gets its database address and seed switch per environment
- no credential is served from the configuration files

## Build and image

- Java 27, Gradle 9.8.0 (wrapper), Spring Boot 4.1.1, Spring Cloud 2025.1.3. Versions come from `../micro-services/gradle/libs.versions.toml`.
- `Dockerfile` is multi-stage: build on JDK 27, run on a JRE 27 Alpine image as a non-root user, with a health check on `/actuator/health/readiness`. It needs the platform root as a named build context:

```bash
docker build --build-context platform=../micro-services -t cloud-config-service .
```
