# Cloud Config Service

Cloud Config Service is the platform's Spring Cloud Config Server. It serves application/profile configuration documents from a native filesystem backend. Vault supplies sensitive settings; the server should not become a store of committed plaintext secrets.

## Current Technology

Java 21 toolchain, Spring Boot 3.4.7, Spring Cloud 2024.0.1, and Gradle 8.14.3 with its own Groovy-DSL wrapper. Dependencies include Config Server, Vault, Eureka, and Actuator. The application enables `@EnableConfigServer`.

Java 27 is already used in the Docker runtime recipe; the Java 27/Boot 4 source migration is still pending. This infrastructure service does not need business aggregates or its own database.

## Backend And Relationships

The active default profiles are `dev,native`. `FILE_SYSTEM_URL` selects the directory containing configuration files; use the sibling [service-configs](../service-configs/README.md) repository in this workspace.

The Git backend configuration is commented out. Setting `GITHUB_URL` alone does not enable it. Git-backed serving requires an explicit backend configuration/profile change and protected repository credentials.

Consumers request configuration by application and profile, for example `books-service/dev`. Vault imports and environment overrides can supply secrets; consumer builds must actually include the corresponding clients.

## Ports And HTTP Contract

HTTP listens on 9311. The default servlet context is `/config`, configurable through `SERVLET_CONTEXT_PATH`.

| URL | Purpose |
| --- | --- |
| `http://localhost:9311/config/books-service/dev` | Books configuration environment/property sources |
| `http://localhost:9311/config/auth-service/dev` | Authorization configuration |
| `http://localhost:9311/config/actuator/health` | Actuator health with default context |

Some current client imports and Compose health checks omit `/config`. Align URLs with the context or deliberately set the context to `/`; do not mix these contracts.

## Configuration

| Setting | Purpose |
| --- | --- |
| `SPRING_ACTIVE_PROFILE` | Defaults to `dev,native` |
| `FILE_SYSTEM_URL` | Native configuration directory |
| `VAULT_HOST`/`VAULT_PORT`/`VAULT_TOKEN` | Vault connection/authentication |
| `EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE` | Registry endpoint; host runs need localhost:9111 |
| `SERVLET_CONTEXT_PATH` | Defaults to `/config` |

The source still contains a mandatory legacy Vault import at `secret/data/api/keys/dev`. The new IAM secret layout does not automatically provide that obsolete entry. Remove/replace this import during migration or provision the required compatibility secret explicitly for a current-version startup.

## Build And Run

Use a Java 21 Gradle launcher for the current build:

```bash
bash ./gradlew clean test bootJar
```

With Vault available and the current mandatory import satisfied:

```bash
export SPRING_ACTIVE_PROFILE=dev,native
export FILE_SYSTEM_URL=/Users/skakumanu/practice/service-configs
export VAULT_HOST=localhost
export VAULT_TOKEN='<local Vault token>'
export EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE=http://localhost:9111/eureka
bash ./gradlew bootRun
```

```bash
curl http://localhost:9311/config/books-service/dev
curl http://localhost:9311/config/actuator/health
```

These startup commands require the prerequisites above; a full fresh-platform run is not yet verified. Docker builds consume `build/libs/cloud-config-service-1.0.jar`.

## Operations And Pending Work

A missing application/profile result usually means the native path or filenames are wrong. Connection failures may indicate an unready Vault/registry or a context-path mismatch.

Do not expose configuration/Actuator endpoints publicly without security and endpoint restrictions. Responses can include sensitive configuration; sanitize logs and never paste tokens into committed documents.

Remaining work: Java 27/Boot 4 migration, obsolete import removal, context-path alignment, backend/security hardening, and startup/API verification. See [platform orchestration](../micro-services/README.md) and the [checkpoint](../micro-services/IAM_IMPLEMENTATION_CHECKPOINT.md).

## Repository Map

```text
cloud-config-service/
  build.gradle                         Spring Boot 3.4.7 / Spring Cloud 2024.0.1 build
  Dockerfile                           Layered Spring Boot image recipe
  src/main/java/.../SpringCloudConfigServer.java
                                       @SpringBootApplication + @EnableConfigServer
  src/main/resources/application.yaml  Base native backend, Vault, Eureka, Actuator setup
  src/main/resources/application-dev.yaml
  src/main/resources/application-qa.yaml
                                       Profile-specific Vault imports and native backend setup
```

## Architecture Snapshot

```text
Config client
  -> GET /config/{application}/{profile}
  -> cloud-config-service
       -> native filesystem backend at FILE_SYSTEM_URL
       -> optional Vault import for server-side secrets
       -> optional Eureka registration
  -> property sources returned to client
```

This service should serve configuration only. It should not become the owner of service-specific business defaults, database schema decisions, or plaintext credentials. Service owners remain responsible for validating that consumed properties match the application's actual dependencies and Spring version.

## Command Reference

| Task | Command |
| --- | --- |
| Test | `bash ./gradlew test` |
| Build JAR | `bash ./gradlew bootJar` |
| Full build | `bash ./gradlew clean test bootJar` |
| Build image | `docker build -t cloud-config-service:latest .` |
| Health with default context | `curl http://localhost:9311/config/actuator/health` |
| Fetch books dev config | `curl http://localhost:9311/config/books-service/dev` |
| Fetch reviews qa config | `curl http://localhost:9311/config/reviews-service/qa` |

When running in shared Compose, use container addresses:

```bash
export FILE_SYSTEM_URL=/application/service-configs
export VAULT_HOST=vault
export EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE=http://eureka-discovery-service:9111/eureka
```

When running directly on the host, use host addresses:

```bash
export FILE_SYSTEM_URL=/Users/skakumanu/practice/service-configs
export VAULT_HOST=localhost
export EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE=http://localhost:9111/eureka
```

## Operational Notes

- The default context path is `/config`; every client import must include it or the server must be deliberately reconfigured to `/`.
- The active native backend reads files from the local filesystem, so changing GitHub contents has no effect until those files are present on the server filesystem.
- Actuator exposes broad diagnostics, including `env`; keep this endpoint private and avoid returning secrets to untrusted clients.
- The Dockerfile expects `build/libs/cloud-config-service-1.0.jar` to exist before image build.
