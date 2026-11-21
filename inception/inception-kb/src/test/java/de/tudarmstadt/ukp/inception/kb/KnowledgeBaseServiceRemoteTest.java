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
package de.tudarmstadt.ukp.inception.kb;

import static de.tudarmstadt.ukp.inception.kb.RepositoryType.LOCAL;
import static de.tudarmstadt.ukp.inception.kb.RepositoryType.REMOTE;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.restoreSslVerification;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.suspendSslVerification;
import static de.tudarmstadt.ukp.inception.kb.util.TestFixtures.assumeEndpointIsAvailable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD;

import java.io.File;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.transaction.annotation.Transactional;

import de.tudarmstadt.ukp.inception.documents.api.RepositoryPropertiesImpl;
import de.tudarmstadt.ukp.inception.kb.config.KnowledgeBasePropertiesImpl;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.kb.graph.KBProperty;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.kb.util.TestFixtures;
import de.tudarmstadt.ukp.inception.kb.reification.Reification;
import de.tudarmstadt.ukp.inception.kb.yaml.KnowledgeBaseMapping;
import de.tudarmstadt.ukp.inception.kb.yaml.KnowledgeBaseProfile;

@Tag("slow")
@Transactional
@DataJpaTest( //
        showSql = false, //
        properties = { //
                "spring.main.banner-mode=off" })
@Execution(SAME_THREAD)
public class KnowledgeBaseServiceRemoteTest
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private KnowledgeBaseServiceImpl sut;

    @TempDir
    File repoPath;

    @Autowired
    private TestEntityManager testEntityManager;

    // @BeforeAll
    // public static void setUpOnce() throws Exception
    // {
    // System.setProperty("org.eclipse.rdf4j.repository.debug", "true");
    // }

    @BeforeEach
    public void testWatcher(TestInfo aTestInfo)
    {
        var methodName = aTestInfo.getTestMethod().map(Method::getName).orElse("<unknown>");
        LOG.info("=== {} === {} =====================", methodName, aTestInfo.getDisplayName());

        suspendSslVerification();
    }

    public void setUp(TestConfiguration aSutConfig) throws Exception
    {
        if (aSutConfig.getKnowledgeBase().getType() == REMOTE) {
            assumeEndpointIsAvailable(aSutConfig.getDataUrl());
        }

        var kb = aSutConfig.getKnowledgeBase();

        var repoProps = new RepositoryPropertiesImpl();
        repoProps.setPath(repoPath);
        var kbProperties = new KnowledgeBasePropertiesImpl();
        var entityManager = testEntityManager.getEntityManager();
        var testFixtures = new TestFixtures(testEntityManager);
        sut = new KnowledgeBaseServiceImpl(repoProps, kbProperties, entityManager);
        var project = testFixtures.createProject("Test project");
        kb.setProject(project);
        if (kb.getType() == LOCAL) {
            sut.registerKnowledgeBase(kb, sut.getNativeConfig());
            sut.updateKnowledgeBase(kb, sut.getKnowledgeBaseConfig(kb));
            importKnowledgeBase(aSutConfig.getKnowledgeBase(), aSutConfig.getDataUrl());
        }
        else if (kb.getType() == REMOTE) {
            assumeEndpointIsAvailable(aSutConfig.getDataUrl());
            sut.registerKnowledgeBase(kb, sut.getRemoteConfig(aSutConfig.getDataUrl()));
            sut.updateKnowledgeBase(kb, sut.getKnowledgeBaseConfig(kb));
        }
        else {
            throw new IllegalStateException(
                    "Unknown type: " + aSutConfig.getKnowledgeBase().getType());
        }
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        if (testEntityManager != null) {
            testEntityManager.clear();
        }

        if (sut != null) {
            sut.destroy();
        }

        restoreSslVerification();
    }

    @ParameterizedTest(name = "{index}: KB = {0}")
    @MethodSource("data")
    public void thatRootConceptsCanBeRetrieved(TestConfiguration aSutConfig) throws Exception
    {
        setUp(aSutConfig);

        var kb = aSutConfig.getKnowledgeBase();

        var duration = System.currentTimeMillis();
        List<KBHandle> rootConceptKBHandle = sut.listRootConcepts(kb, true);
        duration = System.currentTimeMillis() - duration;

        LOG.info("Root concepts retrieved : {}", rootConceptKBHandle.size());
        LOG.info("Time required           : {} ms", duration);
        rootConceptKBHandle.stream().limit(10).forEach(h -> LOG.info("   {}", h));

        assertThat(rootConceptKBHandle).as("Check that root concept list is not empty")
                .isNotEmpty();
        for (var expectedRoot : aSutConfig.getRootIdentifier()) {
            assertThat(rootConceptKBHandle.stream().map(KBHandle::getIdentifier))
                    .as("Check that root concept is retrieved").contains(expectedRoot);
        }
    }

    @ParameterizedTest(name = "{index}: KB = {0}")
    @MethodSource("data")
    public void thatPropertyListCanBeRetrieved(TestConfiguration aSutConfig) throws Exception
    {
        setUp(aSutConfig);

        var kb = aSutConfig.getKnowledgeBase();

        var duration = System.currentTimeMillis();
        List<KBProperty> propertiesKBHandle = sut.listProperties(kb, true);
        duration = System.currentTimeMillis() - duration;

        LOG.info("Properties retrieved : {}", propertiesKBHandle.size());
        LOG.info("Time required        : {} ms", duration);
        propertiesKBHandle.stream().limit(10).forEach(h -> LOG.info("   {}", h));

        assertThat(propertiesKBHandle).as("Check that property list is not empty").isNotEmpty();
    }

    @ParameterizedTest(name = "{index}: KB = {0}")
    @MethodSource("data")
    public void thatParentListCanBeRetireved(TestConfiguration aSutConfig) throws Exception
    {
        setUp(aSutConfig);

        KnowledgeBase kb = aSutConfig.getKnowledgeBase();

        long duration = System.currentTimeMillis();
        List<KBHandle> parentList = sut.getParentConceptList(kb, aSutConfig.getTestIdentifier(),
                true);
        duration = System.currentTimeMillis() - duration;

        LOG.info("Parents for          : {}", aSutConfig.getTestIdentifier());
        LOG.info("Parents retrieved    : {}", parentList.size());
        LOG.info("Time required        : {} ms", duration);
        parentList.stream().limit(10).forEach(h -> LOG.info("   {}", h));

        assertThat(parentList).as("Check that parent list is not empty").isNotEmpty();
    }

    public static List<TestConfiguration> data() throws Exception
    {
        var PROFILES = KnowledgeBaseProfile.readKnowledgeBaseProfiles();
        int maxResults = 1000;

        var kbList = new ArrayList<TestConfiguration>();

        // A local knowledge base, so that the local code path is covered and that at least one
        // configuration runs without network access
        kbList.add(localLibraryOntology(maxResults));

        kbList.add(TestConfiguration.fromProfile(PROFILES.get("wikidata"),
                "http://www.wikidata.org/entity/Q5", maxResults));

        kbList.add(TestConfiguration.fromProfile(PROFILES.get("db_pedia"),
                "http://dbpedia.org/ontology/Organisation", maxResults));

        // YAGO seems to have a problem atm 29-04-2023
        // kbList.add(TestConfiguration.fromProfile(PROFILES.get("yago"),
        // "http://yago-knowledge.org/resource/Elvis_Presley", maxResults));

        // zbw-stw-economics has no root-concepts defined in the profile, so we specify them here
        kbList.add(TestConfiguration.fromProfile(PROFILES.get("zbw-stw-economics"),
                "http://zbw.eu/stw/thsys/71020", maxResults, Set.of("http://zbw.eu/stw/thsys/a")));

        // MeSH (OpenLink Virtuoso, see issue #6125). Test identifier is the "Insulin" descriptor
        // (parent: "Proinsulin" via meshv:broaderDescriptor); "Endocrine System" is a parent-less
        // top descriptor and thus a root concept. The schema (property/class definitions) lives in
        // the separate .../mesh/vocab graph which the profile pulls in as an additional dataset, so
        // properties are reachable.
        kbList.add(TestConfiguration.fromProfile(PROFILES.get("mesh"),
                "http://id.nlm.nih.gov/mesh/D007328", maxResults,
                Set.of("http://id.nlm.nih.gov/mesh/D004703")));

        // Commenting this out for the moment because we expect that every ontology contains
        // property definitions. However, this one does not include any property definitions!
        // kbList.add(TestConfiguration.fromProfile(PROFILES.get("zbw-gnd"),
        // "...", maxResults));

        return kbList;
    }

    // Helper

    private static TestConfiguration localLibraryOntology(int aMaxResults)
    {
        var kb = new KnowledgeBase();
        kb.setName("Library ontology (local)");
        kb.setType(LOCAL);
        kb.setReification(Reification.NONE);
        kb.setFullTextSearchIri(IriConstants.FTS_RDF4J_LUCENE.stringValue());
        kb.applyMapping(new KnowledgeBaseMapping(OWL.CLASS.stringValue(),
                RDFS.SUBCLASSOF.stringValue(), RDF.TYPE.stringValue(),
                RDFS.SUBPROPERTYOF.stringValue(), RDFS.COMMENT.stringValue(),
                RDFS.LABEL.stringValue(), RDF.PROPERTY.stringValue(), RDFS.LABEL.stringValue(),
                RDFS.COMMENT.stringValue(), OWL.DEPRECATED.stringValue()));
        kb.setMaxResults(aMaxResults);

        return new TestConfiguration("data/library_ontology.ttl", kb,
                "http://example.org/test/library#Book",
                Set.of("http://example.org/test/library#Agent",
                        "http://example.org/test/library#Work"),
                Map.of());
    }

    private void importKnowledgeBase(KnowledgeBase aKnowledgeBase, String resourceName)
        throws Exception
    {
        ClassLoader classLoader = KnowledgeBaseServiceRemoteTest.class.getClassLoader();
        String fileName = classLoader.getResource(resourceName).getFile();
        try (InputStream is = classLoader.getResourceAsStream(resourceName)) {
            sut.importData(aKnowledgeBase, fileName, is);
        }
    }

    public static KnowledgeBase setOWLSchemaMapping(KnowledgeBase kb)
    {
        kb.setClassIri(OWL.CLASS.stringValue());
        kb.setSubclassIri(RDFS.SUBCLASSOF.stringValue());
        kb.setTypeIri(RDF.TYPE.stringValue());
        kb.setDescriptionIri(RDFS.COMMENT.stringValue());
        kb.setLabelIri(RDFS.LABEL.stringValue());
        kb.setPropertyTypeIri(RDF.PROPERTY.stringValue());
        return kb;
    }

    private static class TestConfiguration
    {
        private final String url;
        private final KnowledgeBase kb;
        private final String testIdentifier;
        private final Set<String> rootIdentifier;
        private final Map<String, String> parentChildIdentifier;

        public TestConfiguration(String aUrl, KnowledgeBase aKb, String aTestIdentifier,
                Set<String> aRootIdentifier, Map<String, String> aParentChildIdentifier)
        {
            super();
            url = aUrl;
            kb = aKb;
            testIdentifier = aTestIdentifier;
            rootIdentifier = aRootIdentifier;
            parentChildIdentifier = aParentChildIdentifier;
        }

        static TestConfiguration fromProfile(KnowledgeBaseProfile aProfile, String aTestIdentifier,
                int aMaxResults)
        {
            return fromProfile(aProfile, aTestIdentifier, aMaxResults,
                    new HashSet<>(aProfile.getRootConcepts()));
        }

        static TestConfiguration fromProfile(KnowledgeBaseProfile aProfile, String aTestIdentifier,
                int aMaxResults, Set<String> aExpectedRootConcepts)
        {
            var kb = new KnowledgeBase();
            kb.setName(aProfile.getName());
            kb.setType(aProfile.getType());
            kb.setReification(aProfile.getReification());
            kb.setFullTextSearchIri(aProfile.getAccess().getFullTextSearchIri());
            kb.applyMapping(aProfile.getMapping());
            kb.applyRootConcepts(aProfile);
            kb.setDefaultLanguage(aProfile.getDefaultLanguage());
            kb.setMaxResults(aMaxResults);
            if (aProfile.getDefaultDataset() != null) {
                kb.setDefaultDatasetIri(aProfile.getDefaultDataset());
            }
            if (aProfile.getAdditionalDatasets() != null) {
                kb.setAdditionalDatasetIris(aProfile.getAdditionalDatasets());
            }
            var accessUrl = aProfile.getAccess().getAccessUrl();
            if (aProfile.getType() == LOCAL) {
                accessUrl = accessUrl.replaceFirst("^classpath:", "");
            }
            return new TestConfiguration(accessUrl, kb, aTestIdentifier, aExpectedRootConcepts,
                    Map.of());
        }

        public KnowledgeBase getKnowledgeBase()
        {
            return kb;
        }

        public String getDataUrl()
        {
            return url;
        }

        public String getTestIdentifier()
        {
            return testIdentifier;
        }

        public Set<String> getRootIdentifier()
        {
            return rootIdentifier;
        }

        @SuppressWarnings("unused")
        public Map<String, String> getParentChildIdentifier()
        {
            return parentChildIdentifier;
        }

        @Override
        public String toString()
        {
            return kb.getName();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = { //
            "de.tudarmstadt.ukp.inception.kb.model", //
            "de.tudarmstadt.ukp.clarin.webanno.model" })
    public static class SpringConfig
    {
        // No content
    }
}
