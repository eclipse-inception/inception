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

import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.restoreSslVerification;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.suspendSslVerification;
import static de.tudarmstadt.ukp.inception.kb.util.TestFixtures.assumeEndpointIsAvailable;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.transaction.annotation.Transactional;

import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.inception.documents.api.RepositoryProperties;
import de.tudarmstadt.ukp.inception.documents.api.RepositoryPropertiesImpl;
import de.tudarmstadt.ukp.inception.kb.config.KnowledgeBaseProperties;
import de.tudarmstadt.ukp.inception.kb.config.KnowledgeBasePropertiesImpl;
import de.tudarmstadt.ukp.inception.kb.graph.KBConcept;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.kb.graph.KBInstance;
import de.tudarmstadt.ukp.inception.kb.graph.KBObject;
import de.tudarmstadt.ukp.inception.kb.graph.KBProperty;
import de.tudarmstadt.ukp.inception.kb.graph.KBStatement;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.kb.reification.Reification;
import de.tudarmstadt.ukp.inception.kb.util.TestFixtures;
import de.tudarmstadt.ukp.inception.kb.yaml.KnowledgeBaseProfile;
import jakarta.persistence.EntityManager;

@Tag("slow")
@Transactional
@DataJpaTest( //
        showSql = false, //
        properties = { //
                "spring.main.banner-mode=off" })
public class KnowledgeBaseServiceImplWikiDataIntegrationTest
{
    static {
        // System.setProperty("org.eclipse.rdf4j.repository.debug", "true");
        System.setProperty("spring.main.banner-mode", "off");
    }

    private static final String PROJECT_NAME = "Test project";

    @TempDir
    public File tempDir;

    @Autowired
    private TestEntityManager testEntityManager;

    private KnowledgeBaseServiceImpl sut;
    private KnowledgeBase kb;

    private TestFixtures testFixtures;
    private static Map<String, KnowledgeBaseProfile> PROFILES;

    public static Collection<Object[]> data()
    {
        return Arrays.stream(Reification.values()) //
                .map(r -> new Object[] { r }) //
                .collect(toList());
    }

    private void setUp(Reification reification) throws Exception
    {
        suspendSslVerification();

        PROFILES = KnowledgeBaseProfile.readKnowledgeBaseProfiles();
        String wikidataAccessUrl = PROFILES.get("wikidata").getAccess().getAccessUrl();
        assumeEndpointIsAvailable(wikidataAccessUrl);

        RepositoryProperties repoProps = new RepositoryPropertiesImpl();
        repoProps.setPath(tempDir);
        KnowledgeBaseProperties kbProperties = new KnowledgeBasePropertiesImpl();
        EntityManager entityManager = testEntityManager.getEntityManager();
        testFixtures = new TestFixtures(testEntityManager);
        sut = new KnowledgeBaseServiceImpl(repoProps, kbProperties, entityManager);
        Project project = createProject();
        kb = buildKnowledgeBase(project, reification);
        sut.registerKnowledgeBase(kb, sut.getRemoteConfig(wikidataAccessUrl));
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        restoreSslVerification();

        testEntityManager.clear();

        if (sut != null) {
            sut.destroy();
        }
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readConcept_WithNonexistentConcept_ShouldReturnEmptyResult(Reification reification)
        throws Exception
    {
        setUp(reification);

        Optional<KBConcept> savedConcept = sut.readConcept(kb,
                "https://nonexistent.identifier.test", true);
        assertThat(savedConcept.isPresent()).as("Check that no concept was read").isFalse();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readConcept_WithExistentConcept_ShouldReturnResult(Reification reification)
        throws Exception
    {
        setUp(reification);

        // Q11344 is "chemical element", a long-established class with stable subclasses
        Optional<KBConcept> concept = sut.readConcept(kb, "http://www.wikidata.org/entity/Q11344",
                true);
        assertThat(concept).as("Check that the concept exists").isPresent();
        assertThat(concept.get().getName()).as("Check that concept has the same UI label")
                .isEqualTo("chemical element");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void listChildConcept_WithExistentConcept_ShouldReturnResult(Reification reification)
        throws Exception
    {
        setUp(reification);

        List<KBHandle> children = sut.listChildConcepts(kb, "http://www.wikidata.org/entity/Q11344",
                true);

        assertThat(children).as("Check that well-established subclasses are found")
                .extracting(KBHandle::getIdentifier) //
                .contains("http://www.wikidata.org/entity/Q19609", // noble gases
                        "http://www.wikidata.org/entity/Q19605", // halogens
                        "http://www.wikidata.org/entity/Q19577"); // actinide
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void listRootConcepts(Reification reification) throws Exception
    {
        setUp(reification);

        Stream<String> rootConcepts = sut.listRootConcepts(kb, false).stream()
                .map(KBHandle::getIdentifier);
        String expectedInstances = "http://www.wikidata.org/entity/Q35120";

        assertThat(rootConcepts).as("Check that root concepts have been found")
                .contains(expectedInstances);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void listProperties(Reification reification) throws Exception
    {
        setUp(reification);

        Stream<String> properties = sut.listProperties(kb, true).stream()
                .map(KBObject::getIdentifier);

        assertThat(properties).as("Check that properties have been found")
                .hasSize(kb.getMaxResults());
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readInstance_WithNonexistentInstance_ShouldReturnEmptyResult(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        Optional<KBInstance> savedInstance = sut.readInstance(kb,
                "https://nonexistent.identifier.test");

        assertThat(savedInstance.isPresent()).as("Check that no instance was read").isFalse();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void listInstances(Reification reification) throws Exception
    {
        setUp(reification);

        Stream<String> instances = sut
                .listInstances(kb, "http://www.wikidata.org/entity/Q2897", true).stream()
                .map(KBHandle::getIdentifier);
        String[] expectedInstances = { "http://www.wikidata.org/entity/Q22663448",
                "http://www.wikidata.org/entity/Q22663448",
                "http://www.wikidata.org/entity/Q30059050" };
        assertThat(instances).as("Check that instances have been found")
                .contains(expectedInstances);

    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void listStatements(Reification reification) throws Exception
    {
        setUp(reification);

        KBHandle handle = new KBHandle("http://www.wikidata.org/entity/Q50556889");

        Stream<String> properties = sut.listStatements(kb, handle, true).stream()
                .map(KBStatement::getProperty).map(KBProperty::getIdentifier);

        if (reification == Reification.NONE) {
            String[] expectedInstances = { "http://www.wikidata.org/prop/P2894",
                    "http://www.wikidata.org/prop/direct/P2894",
                    "http://www.wikidata.org/prop/direct/P31", "http://www.wikidata.org/prop/P31" };
            assertThat(properties).as("Check that properties have been found")
                    .contains(expectedInstances);
        }
        else {
            String[] expectedInstances = { "http://www.wikidata.org/prop/P585",
                    "http://www.wikidata.org/prop/P31", "http://www.wikidata.org/prop/P361",
                    "http://www.wikidata.org/prop/P2894", "http://www.wikidata.org/prop/P31" };
            assertThat(properties).as("Check that properties have been found")
                    .contains(expectedInstances);
        }
    }

    // Helper

    private Project createProject()
    {
        return testFixtures.createProject(PROJECT_NAME);
    }

    private KnowledgeBase buildKnowledgeBase(Project project, Reification reification)
    {
        var profile = PROFILES.get("wikidata");
        var kb = new KnowledgeBase();
        kb.applyProfile(profile);
        kb.setProject(project);
        kb.setReification(reification);
        kb.setMaxResults(1000);
        return kb;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = { "de.tudarmstadt.ukp.inception.kb.model",
            "de.tudarmstadt.ukp.clarin.webanno.model" })
    public static class SpringConfig
    {
        // No content
    }
}
