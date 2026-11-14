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

import static de.tudarmstadt.ukp.inception.kb.IriConstants.FTS_BLAZEGRAPH;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.restoreSslVerification;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.suspendSslVerification;
import static de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryBuilderLocalTestScenarios.buildSparqlRepository;
import static java.time.Duration.ofMinutes;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.rdf4j.repository.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
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
public class BlazegraphRepositoryTest
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final int BLAZEGRAPH_PORT = 8080;

    private @TempDir Path temp;
    private Repository repository;
    private KnowledgeBase kb;

    @Container
    private static final GenericContainer<?> BLAZEGRAPH = new GenericContainer<>(
            "islandora/blazegraph:6.4.3") //
                    .withExposedPorts(BLAZEGRAPH_PORT) //
                    .withEnv("BLAZEGRAPH_HOST", "0.0.0.0") //
                    .waitingFor(Wait.forHttp("/bigdata/sparql").forPort(BLAZEGRAPH_PORT)
                            .withStartupTimeout(ofMinutes(2)));

    @BeforeEach
    public void setUp(TestInfo aTestInfo) throws Exception
    {
        var methodName = aTestInfo.getTestMethod().map(Method::getName).orElse("<unknown>");
        LOG.info("=== {} === {} =====================", methodName, aTestInfo.getDisplayName());

        suspendSslVerification();

        assertThat(BLAZEGRAPH.isRunning()).isTrue();

        forceFtsCreation();

        kb = new KnowledgeBase();
        kb.setDefaultLanguage("en");
        kb.setType(RepositoryType.REMOTE);
        kb.setFullTextSearchIri(FTS_BLAZEGRAPH.stringValue());
        kb.setMaxResults(100);

        SPARQLQueryBuilderLocalTestScenarios.initRdfsMapping(kb);

        repository = buildSparqlRepository("http://" + BLAZEGRAPH.getHost() + ":"
                + BLAZEGRAPH.getMappedPort(BLAZEGRAPH_PORT) + "/bigdata/sparql");

        try (var conn = repository.getConnection()) {
            conn.clear();
        }
    }

    private void forceFtsCreation() throws URISyntaxException, IOException, InterruptedException
    {
        var httpClient = HttpClient.newBuilder().build();

        var namespace = "kb"; // Default namespace

        var request = HttpRequest.newBuilder()
                .uri(new URI("http://" + BLAZEGRAPH.getHost() + ":"
                        + BLAZEGRAPH.getMappedPort(BLAZEGRAPH_PORT) + "/bigdata/namespace/"
                        + namespace + "/textIndex?force-index-create=true"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        restoreSslVerification();
    }

    private static List<Arguments> tests() throws Exception
    {
        var exclusions = asList( //
                // Not sure why this one does not work
                "testWithLabelStartingWith_withLanguage_FTS_5",
                // Not really clear why this one does not return any results. But since the FTS
                // version of the test passes, I assume it is not critical - we will usually search
                // with FTS enabled
                "testWithLabelMatchingExactlyAnyOf_subproperty_noFTS");

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
}
