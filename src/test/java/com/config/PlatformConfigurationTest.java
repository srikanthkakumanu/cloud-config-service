package com.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serves the platform's real configuration repository (../service-configs) and checks what each
 * service would receive in each environment, so a missing or misnamed file is caught here and
 * not when a service fails to start.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PlatformConfigurationTest {

	private static final Path REPOSITORY = Path.of("..", "service-configs");

	@Container
	static final VaultContainer<?> vault = new VaultContainer<>("hashicorp/vault:2.1.1").withVaultToken("test-root-token");

	@Value("${local.server.port}")
	private int port;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("VAULT_HOST", vault::getHost);
		registry.add("VAULT_PORT", () -> vault.getMappedPort(8200));
		registry.add("CONFIG_REPO_LOCATION", () -> REPOSITORY.toUri().toString());
	}

	@ParameterizedTest
	@ValueSource(strings = { "user-service", "auth-service", "api-gateway", "books-service", "video-service" })
	void everyServiceHasAFileForEachEnvironmentAndTheSharedSettings(String service) {
		for (String suffix : new String[] { "", "-dev", "-qa", "-prod" }) {
			assertThat(Files.exists(REPOSITORY.resolve(service + suffix + ".yml"))).as("%s%s.yml exists", service, suffix)
					.isTrue();
		}
		for (String environment : new String[] { "dev", "qa", "prod" }) {
			assertThat(configuration(service, environment)).as("%s in %s", service, environment)
					.containsEntry("platform.keycloak.realm", "platform")
					.containsEntry("platform.security.jwt.algorithms", "RS256")
					.containsKeys("platform.security.jwt.issuer-uri", "eureka.client.service-url.defaultZone");
		}
	}

	/** The gateway keeps its client ID in its own bundled file; the services get theirs from here. */
	@ParameterizedTest
	@ValueSource(strings = { "user-service", "auth-service", "books-service", "video-service" })
	void everyServiceIsGivenItsOwnClientIdAndAudience(String service) {
		for (String environment : new String[] { "dev", "qa", "prod" }) {
			assertThat(configuration(service, environment)).as("%s in %s", service, environment)
					.containsEntry("platform.keycloak.client-id", service)
					.containsEntry("platform.security.jwt.audience", service);
		}
	}

	@Test
	void booksServiceIsConfiguredPerEnvironment() {
		var dev = configuration("books-service", "dev");
		var qa = configuration("books-service", "qa");
		var prod = configuration("books-service", "prod");

		// The database address has a local default in dev only; elsewhere it must be supplied.
		assertThat(dev).containsEntry("spring.datasource.url", "${DATABASE_URL:jdbc:postgresql://localhost:5432/booksdb}");
		assertThat(qa).containsEntry("spring.datasource.url", "${DATABASE_URL}");
		assertThat(prod).containsEntry("spring.datasource.url", "${DATABASE_URL}");
		// The starter catalog is development data.
		assertThat(dev).containsEntry("books.seed.enabled", true);
		assertThat(qa).containsEntry("books.seed.enabled", false);
		assertThat(prod).containsEntry("books.seed.enabled", false);
		// API documentation is switched off in production only.
		assertThat(prod).containsEntry("springdoc.api-docs.enabled", false);
		assertThat(dev).doesNotContainKey("springdoc.api-docs.enabled");
	}

	@ParameterizedTest
	@ValueSource(strings = { "user-service", "auth-service", "api-gateway", "books-service", "video-service" })
	void noCredentialIsServedFromTheConfigurationFiles(String service) {
		for (String environment : new String[] { "dev", "qa", "prod" }) {
			assertThat(configuration(service, environment).keySet()).as("%s in %s", service, environment)
					.noneMatch(key -> key.endsWith("password") || key.endsWith("username") || key.endsWith("secret")
							|| key.endsWith(".user") || key.endsWith("token"));
		}
	}

	/** Property sources are listed most specific first, so earlier ones win. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> configuration(String service, String environment) {
		Map<String, Object> response = RestClient.create("http://localhost:" + port).get()
				.uri("/{service}/{environment}", service, environment).retrieve()
				.body(new ParameterizedTypeReference<>() {
				});
		var result = new LinkedHashMap<String, Object>();
		for (var source : (Iterable<Map<String, Object>>) response.get("propertySources")) {
			((Map<String, Object>) source.get("source")).forEach(result::putIfAbsent);
		}
		return result;
	}
}
