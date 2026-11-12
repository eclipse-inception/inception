/*
 * Licensed to the Technische Universität Darmstadt under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The Technische Universität Darmstadt
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.kb.querybuilder;

import static de.tudarmstadt.ukp.inception.kb.IriConstants.FTS_MARKLOGIC;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.restoreSslVerification;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.suspendSslVerification;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofMinutes;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.eclipse.rdf4j.repository.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import de.tudarmstadt.ukp.inception.kb.RepositoryType;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryBuilderLocalTestScenarios.Scenario;

/**
 * Integration test running the shared FTS scenarios against a dockerized MarkLogic Server.
 * <p>
 * Running this test requires accepting the (free) MarkLogic developer license - see
 * <a href="https://developer.marklogic.com/free-developer/">developer.marklogic.com</a>. The image
 * itself is publicly available on Docker Hub at {@code progressofficial/marklogic-db}.
 * <p>
 * The test brings up a single MarkLogic node and then, via the Management REST API:
 * <ol>
 * <li>enables the triple index (required for SPARQL - otherwise queries fail with
 * {@code XDMP-TRPLIDXNOTFOUND}) and wildcard searches (so {@code cts:word-query} prefix lookups
 * resolve from the index) on the {@code Documents} database, and</li>
 * <li>switches the App-Services HTTP server (port 8000, which serves the {@code /v1/graphs/sparql}
 * endpoint) from the default digest authentication to basic authentication, so the RDF4J
 * {@link org.eclipse.rdf4j.repository.sparql.SPARQLRepository SPARQLRepository} can authenticate
 * using the credentials embedded in the endpoint URL.</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
public class MarkLogicRepositoryTest
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final String MARKLOGIC_IMAGE_AMD64 = "progressofficial/marklogic-db:11.3.7-ubi9-2.3.1";
    private static final String MARKLOGIC_IMAGE_ARM64 = "progressofficial/marklogic-db:11.3.7-ubi9-arm-2.3.1";
    private static final String MARKLOGIC_IMAGE = markLogicImage();

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASSWORD = "admin";

    private static final String GRAPH_IRI = "http://testgraph";

    /** App-Services HTTP server - serves the REST API including {@code /v1/graphs/sparql}. */
    private static final int APP_SERVICES_PORT = 8000;
    /** Management HTTP server - serves the {@code /manage/v2} configuration API. */
    private static final int MANAGE_PORT = 8002;

    /** The database backing the App-Services server, where the triples are stored and indexed. */
    private static final String CONTENT_DATABASE = "Documents";

    @Container
    private static final GenericContainer<?> MARKLOGIC = new GenericContainer<>(MARKLOGIC_IMAGE) //
            .withExposedPorts(APP_SERVICES_PORT, 8001, MANAGE_PORT) //
            .withLogConsumer(new Slf4jLogConsumer(LOG)) //
            .withEnv("MARKLOGIC_INIT", "true") //
            .withEnv("MARKLOGIC_ADMIN_USERNAME", ADMIN_USER) //
            .withEnv("MARKLOGIC_ADMIN_PASSWORD", ADMIN_PASSWORD) //
            .waitingFor(Wait.forHttp("/adminUI").forPort(8001)
                    .forStatusCodeMatching(code -> code == 200 || code == 401 || code == 403)
                    .withStartupTimeout(ofMinutes(5)));

    private Repository repository;
    private KnowledgeBase kb;

    /**
     * MarkLogic publishes the amd64 and arm64 builds under separate tags instead of one
     * multi-architecture tag, so pick the one matching the host to run natively.
     */
    private static String markLogicImage()
    {
        var arch = System.getProperty("os.arch");
        return Set.of("aarch64", "arm64").contains(arch) ? MARKLOGIC_IMAGE_ARM64
                : MARKLOGIC_IMAGE_AMD64;
    }

    @BeforeAll
    static void setUpClass() throws Exception
    {
        assertThat(MARKLOGIC.isRunning()).isTrue();

        // The container is "up" (HTTP servers respond) well before MarkLogic has finished
        // initializing the admin user, so the Management API initially answers with 401/403/503.
        // Wait until it actually accepts authenticated requests before configuring anything.
        LOG.info("Waiting for the MarkLogic Management API to become usable ...");
        awaitOk(manageUrl("/manage/v2/hosts"), ofMinutes(3));

        LOG.info("Enabling triple and wildcard indexes on database [{}] ...", CONTENT_DATABASE);
        managePut("/manage/v2/databases/" + CONTENT_DATABASE + "/properties", //
                """
                        { \
                          "triple-index": true, \
                          "three-character-searches": true, \
                          "trailing-wildcard-searches": true, \
                          "trailing-wildcard-word-positions": true \
                        }""");

        LOG.info("Switching App-Services server to basic authentication ...");
        managePut("/manage/v2/servers/App-Services/properties?group-id=Default",
                "{ \"authentication\": \"basic\" }");

        // Changing the authentication scheme restarts the App-Services server, so wait until the
        // SPARQL endpoint is reachable again before letting any tests run against it.
        LOG.info("Waiting for the SPARQL endpoint to come back up ...");
        awaitOk(sparqlEndpointUrl() + "?query=" + URLEncoder.encode("ASK {}", UTF_8), ofMinutes(2));

        // Enabling the wildcard / 3-character indexes triggers a background reindex of the
        // database. Until that settles, cts:word-query lookups can return incomplete results, so
        // wait until a probe wildcard query actually finds a freshly inserted triple.
        LOG.info("Waiting for the full-text indexes to become queryable ...");
        awaitFtsReady(ofMinutes(2));

        LOG.info("MarkLogic setup complete");
    }

    private static final String PROBE_GRAPH = "http://inception/marklogic-fts-probe";

    private static void awaitFtsReady(Duration aTimeout) throws Exception
    {
        sparqlUpdate("INSERT DATA { GRAPH <" + PROBE_GRAPH + "> { <" + PROBE_GRAPH
                + "#s> <http://www.w3.org/2000/01/rdf-schema#label> \"marklogicftsprobe\" } }");
        try {
            var query = "PREFIX cts: <http://marklogic.com/cts#> ASK { GRAPH <" + PROBE_GRAPH
                    + "> { ?s ?p ?o FILTER(cts:contains(?o, "
                    + "cts:word-query(\"marklogicftspro*\", \"wildcarded\"))) } }";
            awaitBodyContains(sparqlEndpointUrl() + "?query=" + URLEncoder.encode(query, UTF_8),
                    "true", aTimeout);
        }
        finally {
            sparqlUpdate("DROP SILENT GRAPH <" + PROBE_GRAPH + ">");
        }
    }

    @BeforeEach
    public void setUp(TestInfo aTestInfo) throws Exception
    {
        var methodName = aTestInfo.getTestMethod().map(Method::getName).orElse("<unknown>");
        LOG.info("=== {} === {} =====================", methodName, aTestInfo.getDisplayName());

        suspendSslVerification();

        kb = new KnowledgeBase();
        kb.setDefaultLanguage("en");
        kb.setDefaultDatasetIri(GRAPH_IRI);
        kb.setType(RepositoryType.REMOTE);
        kb.setFullTextSearchIri(FTS_MARKLOGIC.stringValue());
        kb.setMaxResults(100);

        SPARQLQueryBuilderLocalTestScenarios.initRdfsMapping(kb);

        var sparqlEndpoint = "http://" + ADMIN_USER + ":" + ADMIN_PASSWORD + "@"
                + MARKLOGIC.getHost() + ":" + MARKLOGIC.getMappedPort(APP_SERVICES_PORT)
                + "/v1/graphs/sparql";
        repository = SPARQLQueryBuilderLocalTestScenarios.buildSparqlRepository(sparqlEndpoint,
                sparqlEndpoint);

        try (var conn = repository.getConnection()) {
            // MarkLogic rejects CLEAR/DROP of a non-existing graph with XDMP-SPQLNOSUCHGRAPH (other
            // stores no-op), so use the SILENT variant which is idempotent on the first run.
            conn.prepareUpdate("CLEAR SILENT GRAPH <" + GRAPH_IRI + ">").execute();
        }
    }

    @AfterEach
    public void tearDown()
    {
        restoreSslVerification();
    }

    private static List<Arguments> tests() throws Exception
    {
        var exclusions = asList( //
                // Like AllegroGraph: the scenario assumes the FTS also returns candidates that do
                // not contain all search terms (searching "hand structure*" should also surface the
                // "Hand" additional-search-property value), but MarkLogic's cts:word-query requires
                // every term to be present, so "Hand" is not among the reported match terms.
                "thatMatchingAgainstAdditionalSearchPropertiesWorks2");

        return SPARQLQueryBuilderLocalTestScenarios.tests().stream() //
                .filter(scenario -> !exclusions.contains(scenario.name)) //
                .map(scenario -> Arguments.of(scenario.name, scenario)) //
                .toList();
    }

    @ParameterizedTest(name = "{index}: test {0}")
    @MethodSource("tests")
    public void runTests(String aScenarioName, Scenario aScenario) throws Exception
    {
        aScenario.implementation.accept(repository, kb);
    }

    private static String manageUrl(String aPath)
    {
        return "http://" + MARKLOGIC.getHost() + ":" + MARKLOGIC.getMappedPort(MANAGE_PORT) + aPath;
    }

    private static String sparqlEndpointUrl()
    {
        return "http://" + MARKLOGIC.getHost() + ":" + MARKLOGIC.getMappedPort(APP_SERVICES_PORT)
                + "/v1/graphs/sparql";
    }

    private static CloseableHttpClient newAuthenticatedClient()
    {
        var credentials = new BasicCredentialsProvider();
        credentials.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(ADMIN_USER, ADMIN_PASSWORD));
        return HttpClientBuilder.create().setDefaultCredentialsProvider(credentials).build();
    }

    /**
     * Polls the given URL with admin credentials until it answers with HTTP 200 or the timeout
     * elapses. Connection failures and non-200 responses (e.g. 401/403/503 while MarkLogic is still
     * initializing or while a server is restarting) are retried.
     */
    private static void awaitOk(String aUrl, Duration aTimeout) throws Exception
    {
        var deadline = System.currentTimeMillis() + aTimeout.toMillis();
        var lastStatus = "no response";
        while (System.currentTimeMillis() < deadline) {
            try (var client = newAuthenticatedClient();
                    var response = client.execute(RequestBuilder.get(new URI(aUrl)).build())) {
                var statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == 200) {
                    return;
                }
                lastStatus = "HTTP " + statusCode;
            }
            catch (Exception e) {
                lastStatus = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            Thread.sleep(3000);
        }
        throw new IllegalStateException("Timed out waiting for [" + aUrl
                + "] to become available (last: " + lastStatus + ")");
    }

    private static void awaitBodyContains(String aUrl, String aExpected, Duration aTimeout)
        throws Exception
    {
        var deadline = System.currentTimeMillis() + aTimeout.toMillis();
        var lastStatus = "no response";
        while (System.currentTimeMillis() < deadline) {
            try (var client = newAuthenticatedClient();
                    var response = client.execute(RequestBuilder.get(new URI(aUrl)).build())) {
                var statusCode = response.getStatusLine().getStatusCode();
                var body = response.getEntity() != null ? EntityUtils.toString(response.getEntity())
                        : "";
                if (statusCode == 200 && body.contains(aExpected)) {
                    return;
                }
                lastStatus = "HTTP " + statusCode + " body=" + body;
            }
            catch (Exception e) {
                lastStatus = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            Thread.sleep(3000);
        }
        throw new IllegalStateException("Timed out waiting for [" + aUrl + "] to return ["
                + aExpected + "] (last: " + lastStatus + ")");
    }

    private static void sparqlUpdate(String aUpdate) throws Exception
    {
        var request = RequestBuilder.post(new URI(sparqlEndpointUrl())) //
                .setEntity(new StringEntity(aUpdate,
                        ContentType.create("application/sparql-update", UTF_8))) //
                .build();

        try (var client = newAuthenticatedClient(); var response = client.execute(request)) {
            var statusCode = response.getStatusLine().getStatusCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException("SPARQL update failed: HTTP " + statusCode);
            }
        }
    }

    private static void managePut(String aPath, String aJsonBody) throws Exception
    {
        // Applying a configuration change can briefly take the Management/App servers offline, so
        // the immediately following request may see a dropped connection or a 5xx. Retry until it
        // succeeds or the timeout elapses.
        var deadline = System.currentTimeMillis() + ofMinutes(2).toMillis();
        var lastStatus = "no response";
        while (System.currentTimeMillis() < deadline) {
            var request = RequestBuilder.put(new URI(manageUrl(aPath))) //
                    .setEntity(new StringEntity(aJsonBody, ContentType.APPLICATION_JSON)) //
                    .build();
            try (var client = newAuthenticatedClient(); var response = client.execute(request)) {
                var statusCode = response.getStatusLine().getStatusCode();
                // 200/204 = applied; 202 = accepted but a server restart is in progress
                if (statusCode >= 200 && statusCode < 300) {
                    return;
                }
                lastStatus = "HTTP " + statusCode;
                // 4xx (other than auth) won't fix itself by retrying - fail fast.
                if (statusCode >= 400 && statusCode < 500 && statusCode != 401
                        && statusCode != 403) {
                    break;
                }
            }
            catch (Exception e) {
                lastStatus = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            Thread.sleep(3000);
        }
        throw new RuntimeException("Unable to apply MarkLogic configuration [" + aPath + "] (last: "
                + lastStatus + ")");
    }
}
