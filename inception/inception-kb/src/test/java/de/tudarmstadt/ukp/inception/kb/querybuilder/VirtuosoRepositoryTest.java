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

import static de.tudarmstadt.ukp.inception.kb.IriConstants.FTS_VIRTUOSO;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.restoreSslVerification;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.suspendSslVerification;
import static java.time.Duration.ofMinutes;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.base.RepositoryConnectionWrapper;
import org.eclipse.rdf4j.repository.base.RepositoryWrapper;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
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

// The repository has to exist and Virtuoso has to be running for this test to run.
// To create the repository, use the following steps:
// Install Virtuoso, e.g. `brew install virtuoso`
// Start Virtuoso, e.g. `virtuoso-t +foreground +configfile /opt/homebrew/Cellar/virtuoso/7.2.7/var/lib/virtuoso/db/virtuoso.ini`
// Go to http://localhost:8890/conductor -> System Admin -> User Accounts -> SPARQL and add account role SPARQL_UPDATE
// Check if FTS is enabled: `SELECT * from DB.DBA.RDF_OBJ_FT_RULES;`
// Enable FTS: `DB.DBA.RDF_OBJ_FT_RULE_ADD (null, null, 'ALL');`
@Testcontainers(disabledWithoutDocker = true)
public class VirtuosoRepositoryTest
{
    private static final String GRAPH_IRI = "http://testgraph";

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final int VIRTUOSO_PORT = 8890;

    // Pinned: 7.2.17 and the "latest" image (7.2.18-dev at the time of writing) reject SPARQL
    // updates that end with a semicolon, which RDF4J always appends, so no test data can be loaded
    // (https://github.com/openlink/virtuoso-opensource/issues/1486)
    @Container
    private static final GenericContainer<?> VIRTUOSO = new GenericContainer<>(
            "openlink/virtuoso-opensource-7:7.2.16") //
                    .withEnv("DBA_PASSWORD", "secret") //
                    .withExposedPorts(VIRTUOSO_PORT) //
                    .waitingFor(Wait.forHttp("/sparql").forPort(VIRTUOSO_PORT)
                            .withStartupTimeout(ofMinutes(2)));

    // Virtuoso's default authenticated endpoint /sparql-auth/ accepts only Digest authentication,
    // but RDF4J 6 sends Basic credentials and does not answer a Digest challenge. The scenarios
    // therefore run against an endpoint with the same definition that accepts Basic
    // authentication. It still rejects wrong credentials.
    private static final String BASIC_AUTH_ENDPOINT = "/sparql-basic";

    private static final String DIGEST_AUTH_ENDPOINT = "/sparql-auth";

    private Repository repository;

    private KnowledgeBase kb;

    @BeforeAll
    public static void setUpEndpoint() throws Exception
    {
        virtuosoSqlCommand("DB.DBA.VHOST_DEFINE (lpath=>'" + BASIC_AUTH_ENDPOINT
                + "', ppath=>'/!sparql/', is_dav=>1, vsp_user=>'dba', "
                + "auth_fn=>'DB.DBA.HP_AUTH_SPARQL_USER', realm=>'SPARQL', sec=>'basic', "
                + "opts=>vector('noinherit', 1));");
    }

    @BeforeEach
    public void setUp(TestInfo aTestInfo) throws Exception
    {
        var methodName = aTestInfo.getTestMethod().map(Method::getName).orElse("<unknown>");
        LOG.info("=== {} === {} =====================", methodName, aTestInfo.getDisplayName());

        assertThat(VIRTUOSO.isRunning()).isTrue();

        virtuosoSqlCommand("DB.DBA.RDF_OBJ_FT_RULE_ADD (null, null, 'ALL');");
        virtuosoSqlCommand("DB.DBA.VT_BATCH_UPDATE ('DB.DBA.RDF_OBJ', 'OFF', null);");

        suspendSslVerification();

        kb = new KnowledgeBase();
        kb.setDefaultLanguage("en");
        kb.setDefaultDatasetIri(GRAPH_IRI);
        kb.setType(RepositoryType.REMOTE);
        kb.setFullTextSearchIri(FTS_VIRTUOSO.stringValue());
        kb.setMaxResults(100);

        SPARQLQueryBuilderLocalTestScenarios.initRdfsMapping(kb);

        var sparqlRepository = buildRepository(BASIC_AUTH_ENDPOINT);

        // Virtuoso adds new literals to its full text index only in batches, even with batch
        // updates switched off. Without updating the index after each import, the scenarios
        // would search before their data is indexed.
        repository = new RepositoryWrapper(sparqlRepository)
        {
            @Override
            public RepositoryConnection getConnection() throws RepositoryException
            {
                return new RepositoryConnectionWrapper(this, super.getConnection())
                {
                    @Override
                    public void add(InputStream aIn, String aBaseUri, RDFFormat aFormat,
                            Resource... aContexts)
                        throws IOException
                    {
                        super.add(aIn, aBaseUri, aFormat, aContexts);
                        updateFullTextIndex();
                    }
                };
            }
        };

        try (var conn = repository.getConnection()) {
            var ctx = SimpleValueFactory.getInstance().createIRI(kb.getDefaultDatasetIri());
            conn.clear(ctx);
        }
    }

    private static Repository buildRepository(String aEndpoint)
    {
        return SPARQLQueryBuilderLocalTestScenarios
                .buildSparqlRepository("http://dba:secret@" + VIRTUOSO.getHost() + ":"
                        + VIRTUOSO.getMappedPort(VIRTUOSO_PORT) + aEndpoint + "/");
    }

    private static void updateFullTextIndex() throws IOException
    {
        try {
            virtuosoSqlCommand("DB.DBA.VT_INC_INDEX_DB_DBA_RDF_OBJ ();");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while updating the full text index", e);
        }
    }

    static void virtuosoSqlCommand(String command) throws IOException, InterruptedException
    {
        command = command.replace("\"", "\\\"");
        LOG.info("Running virtuoso command: {}", command);
        var result = VIRTUOSO.execInContainer("sh", "-c",
                "echo \"" + command + "\" | isql -U dba -P secret");
        LOG.info("Command exit code: {}", result.getExitCode());
        LOG.info("Command stdout: {}", result.getStdout());
        LOG.info("Command stderr: {}", result.getStderr());
    }

    @AfterEach
    public void tearDown()
    {
        restoreSslVerification();
    }

    private static List<Arguments> tests() throws Exception
    {
        var exclusions = asList( //
                // Virtuoso does not seem to like the VALUES clause used here. Should not be too
                // bad because a similar query using FTS works and Virtuoso should not be used
                // without FTS anyway
                "testWithLabelMatchingExactlyAnyOf_subproperty_noFTS",
                // Seems to be a limitation in Virtuoso:
                // Virtuoso 37000 Error SP031: SPARQL compiler: Object of transitive triple pattern
                // should be variable or QName or literal, not blank node
                "thatPropertyQueryLimitedToDomainDoesNotReturnOutOfScopeResults",
                // These search for the prefix "N". Virtuoso rejects wildcards with fewer than four
                // leading characters (hard-coded in its text index), so FtsAdapterVirtuoso drops
                // such prefixes and finds nothing
                "testWithLabelStartingWith_customLabelProperty",
                "testWithLabelStartingWith_customLabelProperty_rdfXml",
                // FtsAdapterVirtuoso matches the value of withLabelContainingAnyOf() as a whole
                // word, so "auto" finds the label "auto" but not "automobile"
                "testWithLabelStartingWith_withLanguage_FTS_5",
                // Virtuoso matches a multi-word query as a phrase, so "hand structure" does not
                // match the label "Hand" as it does with the other full text indexes
                "thatMatchingAgainstAdditionalSearchPropertiesWorks2",
                // Virtuoso returns the blank node of an owl:Restriction superclass as a root
                // concept - a bug in the roots query
                "thatRootsCanBeRetrieved_owl");

        return SPARQLQueryBuilderLocalTestScenarios.tests().stream() //
                .filter(scenario -> !exclusions.contains(scenario.name)) //
                .map(scenario -> Arguments.of(scenario.name, scenario)) //
                .toList();
    }

    @Disabled("RDF4J 6 sends Basic credentials and does not answer the Digest challenge of "
            + "Virtuoso's default authenticated endpoint")
    @Test
    public void thatUpdatesWorkWithDigestAuthentication() throws Exception
    {
        var digestRepository = buildRepository(DIGEST_AUTH_ENDPOINT);
        try (var conn = digestRepository.getConnection()) {
            var ctx = SimpleValueFactory.getInstance().createIRI(kb.getDefaultDatasetIri());
            conn.clear(ctx);
        }
        finally {
            digestRepository.shutDown();
        }
    }

    @ParameterizedTest(name = "{index}: test {0}")
    @MethodSource("tests")
    public void runTests(String aScenarioName, Scenario aScenario) throws Exception
    {
        aScenario.implementation.accept(repository, kb);
    }
}
