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

import static de.tudarmstadt.ukp.inception.kb.IriConstants.FTS_ALLEGRO_GRAPH;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.restoreSslVerification;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.suspendSslVerification;
import static java.time.Duration.ofMinutes;
import static java.util.Arrays.asList;
import static org.apache.commons.lang3.StringUtils.appendIfMissing;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Base64;
import java.util.List;

import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import de.tudarmstadt.ukp.inception.kb.RepositoryType;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryBuilderLocalTestScenarios.Scenario;

@Testcontainers(disabledWithoutDocker = true)
@EnabledOnOs(architectures = { "amd64", "x86_64" })
public class AllegroGraphRepositoryTest
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final String GRAPH_IRI = "http://testgraph";

    private static final String USER = "dba";
    private static final String PASSWORD = "secret";
    private static final String CATALOG = "root";
    private static final String REPOSITORY = "test";
    private static final int ALLEGRO_GRAPH_PORT = 10035;

    @Container
    private static final GenericContainer<?> ALLEGRO_GRAPH = new GenericContainer<>(
            "franzinc/agraph:v8.5.3") //
                    .withSharedMemorySize(1_000_000_000l) //
                    .withEnv("AGRAPH_SUPER_USER", USER) //
                    .withEnv("AGRAPH_SUPER_PASSWORD", PASSWORD) //
                    .withExposedPorts(ALLEGRO_GRAPH_PORT) //
                    .waitingFor(Wait.forHttp("/").forPort(ALLEGRO_GRAPH_PORT)
                            .withStartupTimeout(ofMinutes(2)));

    private Repository repository;

    private KnowledgeBase kb;

    @BeforeAll
    static void setUpClass() throws Exception
    {
        assertThat(ALLEGRO_GRAPH.isRunning()).isTrue();

        LOG.info("Creating repository: [{}] ...", REPOSITORY);
        ALLEGRO_GRAPH.execInContainer("agtool", "repos", "create", REPOSITORY);
        LOG.info("Created repository: [{}] complete", REPOSITORY);

        LOG.info("Creating freetext index...");
        createIndex(
                "http://" + ALLEGRO_GRAPH.getHost() + ":"
                        + ALLEGRO_GRAPH.getMappedPort(ALLEGRO_GRAPH_PORT),
                USER, PASSWORD, REPOSITORY, "fti");
        LOG.info("Creating freetext index... complete");
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
        kb.setFullTextSearchIri(FTS_ALLEGRO_GRAPH.stringValue());
        kb.setMaxResults(100);

        SPARQLQueryBuilderLocalTestScenarios.initRdfsMapping(kb);

        repository = SPARQLQueryBuilderLocalTestScenarios.buildSparqlRepository(
                "http://" + USER + ":" + PASSWORD + "@" + ALLEGRO_GRAPH.getHost() + ":"
                        + ALLEGRO_GRAPH.getMappedPort(ALLEGRO_GRAPH_PORT) + "/catalogs/" + CATALOG
                        + "/repositories/" + REPOSITORY + "/sparql");

        try (var conn = repository.getConnection()) {
            var ctx = SimpleValueFactory.getInstance().createIRI(kb.getDefaultDatasetIri());
            conn.clear(ctx);
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
                // The test assumes that the FTS will also return results that may not contain all
                // search terms but AllegroGraph will not return such results (e.g. searching for
                // "hand structure*" will not return "hand") - maybe the test should be changed
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

    public static void createIndex(String aBaseUrl, String aUsername, String aPassword,
            String aRepository, String aIndex)
        throws IOException, InterruptedException
    {
        var url = appendIfMissing(aBaseUrl, "/") + "repositories/" + aRepository
                + "/freetext/indices/" + aIndex + "?minimumWordSize=1";

        var auth = aUsername + ":" + aPassword;
        var encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        var request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Authorization", "Basic " + encodedAuth) //
                .PUT(BodyPublishers.noBody()) //
                .build();

        var response = client.send(request, BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Unable to create index: " + response.body() + " ("
                    + response.statusCode() + ")");
        }
    }
}
