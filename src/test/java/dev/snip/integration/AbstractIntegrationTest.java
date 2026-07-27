package dev.snip.integration;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Base for tests that need the real thing: a real Postgres with the real Flyway
 * migrations (partitioned clicks table, plpgsql partition helper) and a real Redis with
 * real Lua scripts. None of that can be faked usefully — an H2 stand-in would not have
 * range partitioning or {@code generate_series}, and an embedded Redis would not run the
 * token-bucket script atomically.
 *
 * <p>Containers are static, so one Postgres and one Redis are shared by every subclass
 * for the whole test run rather than started per class.
 *
 * <p>The whole suite is skipped when Docker is unavailable, so {@code mvn test} still
 * passes on a machine without it. That is a deliberate choice: unit tests must never
 * depend on a daemon, and integration tests must never be silently replaced by weaker
 * ones.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integrationtest")
@EnabledIf("dockerAvailable")
public abstract class AbstractIntegrationTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static final PostgreSQLContainer<?> POSTGRES;
    static final GenericContainer<?> REDIS;

    static {
        if (dockerAvailable()) {
            POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("snip")
                    .withUsername("snip")
                    .withPassword("snip")
                    .withReuse(false);
            REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--maxmemory", "128mb",
                            "--maxmemory-policy", "allkeys-lru", "--appendonly", "no")
                    .waitingFor(Wait.forListeningPort());
            POSTGRES.start();
            REDIS.start();
        } else {
            POSTGRES = null;
            REDIS = null;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (!dockerAvailable()) {
            return;
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // Only values that cannot be known until the containers are up belong here.
        //
        // Everything else lives in application-integrationtest.yml, and the reason is a
        // precedence trap worth remembering: @DynamicPropertySource sits ABOVE
        // @TestPropertySource, so anything set here silently overrides a subclass's own
        // @TestPropertySource. Putting the shared defaults in a profile yml leaves
        // subclasses able to override them, which is what RateLimitIntegrationTest needs.
    }

    @LocalServerPort
    protected int port;

    /**
     * Built on the JDK's {@code java.net.http.HttpClient} rather than the default
     * {@code HttpURLConnection} factory, for two concrete reasons:
     *
     * <ul>
     *   <li>{@code HttpURLConnection} rejects PATCH outright
     *       ({@code ProtocolException: Invalid HTTP method}), and the link-update
     *       endpoint is a PATCH.</li>
     *   <li>It also throws {@code HttpRetryException} rather than returning a 401, which
     *       makes the password-challenge response impossible to assert on.</li>
     * </ul>
     *
     * <p>Redirects are set to NEVER so redirect tests inspect our own 302 instead of
     * chasing it out to the public internet.
     */
    protected final TestRestTemplate rest = buildClient();

    private static TestRestTemplate buildClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        TestRestTemplate template = new TestRestTemplate();
        template.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(httpClient));
        // Default handler throws on 4xx/5xx; tests assert on status codes instead.
        template.getRestTemplate().setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        return template;
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** Same client; the name documents intent at the call site. */
    protected TestRestTemplate noFollow() {
        return rest;
    }

    protected HttpHeaders json(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.set("X-API-Key", apiKey);
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    protected ResponseEntity<Map<String, Object>> createLink(String longUrl, String apiKey) {
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) rest.exchange(
                url("/api/links"), HttpMethod.POST,
                new HttpEntity<>(Map.of("url", longUrl), json(apiKey)),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    protected ResponseEntity<Map<String, Object>> createLink(Map<String, Object> body, String apiKey) {
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) rest.exchange(
                url("/api/links"), HttpMethod.POST,
                new HttpEntity<>(body, json(apiKey)),
                Map.class);
    }
}
