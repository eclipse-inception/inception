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

import static de.tudarmstadt.ukp.inception.kb.IriConstants.FTS_FUSEKI;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.restoreSslVerification;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.suspendSslVerification;
import static de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryBuilderLocalTestScenarios.buildSparqlRepository;
import static java.util.Arrays.asList;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.text.EntityDefinition;
import org.apache.jena.query.text.TextDatasetFactory;
import org.apache.jena.query.text.TextIndexConfig;
import org.apache.jena.query.text.TextIndexLucene;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.lucene.store.MMapDirectory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.inception.kb.RepositoryType;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryBuilderLocalTestScenarios.Scenario;

public class FusekiRepositoryTest
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private @TempDir Path temp;
    private FusekiServer fusekiServer;
    private Repository repository;
    private KnowledgeBase kb;

    @BeforeEach
    public void setUp(TestInfo aTestInfo) throws Exception
    {
        var methodName = aTestInfo.getTestMethod().map(Method::getName).orElse("<unknown>");
        LOG.info("=== {} === {} =====================", methodName, aTestInfo.getDisplayName());

        suspendSslVerification();

        // Local Fuseki in-memory story
        fusekiServer = FusekiServer.create() //
                .add("/fuseki", createFusekiFTSDataset()) //
                .build();
        fusekiServer.start();

        kb = new KnowledgeBase();
        kb.setDefaultLanguage("en");
        kb.setType(RepositoryType.REMOTE);
        kb.setFullTextSearchIri(FTS_FUSEKI.stringValue());
        kb.setMaxResults(100);

        SPARQLQueryBuilderLocalTestScenarios.initRdfsMapping(kb);

        repository = buildSparqlRepository(
                "http://localhost:" + fusekiServer.getPort() + "/fuseki");

        try (RepositoryConnection conn = repository.getConnection()) {
            conn.clear();
        }
    }

    @AfterEach
    public void tearDown()
    {
        restoreSslVerification();

        fusekiServer.stop();
    }

    private static List<Arguments> tests() throws Exception
    {
        var exclusions = asList( //
                // These require additional configuration in Fuseki FTS
                "thatMatchingAgainstAdditionalSearchPropertiesWorks", //
                "testWithLabelMatchingExactlyAnyOf_subproperty", //
                "testWithLabelStartingWith_customLabelProperty",
                "testWithLabelStartingWith_customLabelProperty_rdfXml",
                // Not sure why this one does not work
                "testWithLabelStartingWith_withLanguage_FTS_5",
                // This test returns one match term less than in the RDF4J case - not clear why
                "thatMatchingAgainstAdditionalSearchPropertiesWorks2");

        return SPARQLQueryBuilderLocalTestScenarios.tests().stream() //
                .filter(scenario -> !exclusions.contains(scenario.name))
                .map(scenario -> Arguments.of(scenario.name, scenario))
                .collect(Collectors.toList());
    }

    @ParameterizedTest(name = "{index}: test {0}")
    @MethodSource("tests")
    public void runTests(String aScenarioName, Scenario aScenario) throws Exception
    {
        aScenario.implementation.accept(repository, kb);
    }

    /**
     * Creates a dataset description with FTS support for the RDFS label property.
     * 
     * @throws IOException
     *             if there was an I/O-level problem
     */
    Dataset createFusekiFTSDataset() throws IOException
    {
        var ds1 = TDB2Factory.createDataset();
        var dir = new MMapDirectory(temp);
        var eDef = new EntityDefinition("iri", "text");
        eDef.setPrimaryPredicate(org.apache.jena.vocabulary.RDFS.label);
        var tidxCfg = new TextIndexConfig(eDef);
        tidxCfg.setValueStored(true);
        tidxCfg.setMultilingualSupport(true);
        var tidx = new TextIndexLucene(dir, tidxCfg);
        var ds = TextDatasetFactory.create(ds1, tidx);
        return ds;
    }
}
