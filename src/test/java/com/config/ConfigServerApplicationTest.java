package com.config;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ConfigServerApplicationTest {

	private static final String ROOT_TOKEN = "test-root-token";

	@Container
	static final VaultContainer<?> vault = new VaultContainer<>("hashicorp/vault:2.1.1").withVaultToken(ROOT_TOKEN);

	@Value("${local.server.port}")
	private int port;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("VAULT_HOST", vault::getHost);
		registry.add("VAULT_PORT", () -> vault.getMappedPort(8200));
		registry.add("CONFIG_REPO_LOCATION", () -> "classpath:/config-repo");
	}

	@BeforeAll
	static void seedVault() throws Exception {
		vault.execInContainer("vault", "kv", "put", "-mount=secret", "sample-service",
				"spring.datasource.password=from-vault");
		vault.execInContainer("vault", "kv", "put", "-mount=secret", "other-service",
				"spring.datasource.password=not-for-sample");
	}

	@Test
	void servesFileConfigurationWithProfileOverridingServiceOverridingShared() {
		var properties = flattened(environment("/sample-service/dev", null));

		assertThat(properties).containsEntry("sample.greeting", "from-dev-profile")
				.containsEntry("sample.shared-only", true);
	}

	@Test
	void servesServiceConfigurationForTheDefaultProfile() {
		assertThat(flattened(environment("/sample-service/default", null)))
				.containsEntry("sample.greeting", "from-sample-service");
	}

	@Test
	void addsVaultSecretsForACallerThatPresentsAVaultToken() {
		var properties = flattened(environment("/sample-service/default", ROOT_TOKEN));

		assertThat(properties).containsEntry("spring.datasource.password", "from-vault")
				.containsEntry("sample.greeting", "from-sample-service");
	}

	@Test
	void servesNoSecretsWithoutAVaultToken() {
		assertThat(flattened(environment("/sample-service/default", null)))
				.doesNotContainKey("spring.datasource.password");
	}

	@Test
	void neverMixesInAnotherServicesSecrets() {
		assertThat(flattened(environment("/sample-service/default", ROOT_TOKEN)).values())
				.doesNotContain("not-for-sample");
	}

	@Test
	void reportsReadiness() {
		String health = client().get().uri("/actuator/health/readiness").retrieve().body(String.class);

		assertThat(health).contains("\"status\":\"UP\"");
	}

	private Map<String, Object> environment(String path, String vaultToken) {
		var request = client().get().uri(path);
		if (vaultToken != null) {
			request.header("X-Config-Token", vaultToken);
		}
		return request.retrieve().body(new ParameterizedTypeReference<>() {
		});
	}

	/** Property sources are listed most specific first, so earlier ones win. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> flattened(Map<String, Object> environment) {
		var result = new java.util.LinkedHashMap<String, Object>();
		for (var source : (Iterable<Map<String, Object>>) environment.get("propertySources")) {
			((Map<String, Object>) source.get("source")).forEach(result::putIfAbsent);
		}
		return result;
	}

	private RestClient client() {
		return RestClient.create("http://localhost:" + port);
	}
}
