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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.io.TempDir;
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

import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.inception.documents.api.RepositoryProperties;
import de.tudarmstadt.ukp.inception.documents.api.RepositoryPropertiesImpl;
import de.tudarmstadt.ukp.inception.kb.config.KnowledgeBaseProperties;
import de.tudarmstadt.ukp.inception.kb.config.KnowledgeBasePropertiesImpl;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.kb.graph.KBInstance;
import de.tudarmstadt.ukp.inception.kb.graph.KBProperty;
import de.tudarmstadt.ukp.inception.kb.graph.KBStatement;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.kb.reification.Reification;
import de.tudarmstadt.ukp.inception.kb.util.TestFixtures;
import de.tudarmstadt.ukp.inception.kb.yaml.KnowledgeBaseMapping;
import de.tudarmstadt.ukp.inception.kb.yaml.KnowledgeBaseProfile;
import jakarta.persistence.EntityManager;

@Transactional
@DataJpaTest( //
        showSql = false, //
        properties = { //
                "spring.main.banner-mode=off" })
public class KnowledgeBaseSubPropertyLabelTest
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final String PROJECT_NAME = "Test project";

    @TempDir
    public File tempDir;

    @Autowired
    private TestEntityManager testEntityManager;

    private KnowledgeBaseServiceImpl sut;
    private Project project;
    private KnowledgeBase kb;

    private TestFixtures testFixtures;
    private static Map<String, KnowledgeBaseProfile> PROFILES;

    public static Collection<Object[]> data()
    {
        return Arrays.stream(Reification.values()).map(r -> new Object[] { r })
                .collect(Collectors.toList());
    }

    // @BeforeAll
    // public static void setUpOnce()
    // {
    // System.setProperty("org.eclipse.rdf4j.repository.debug", "true");
    // }

    @BeforeEach
    public void setUp()
    {
        RepositoryProperties repoProps = new RepositoryPropertiesImpl();
        repoProps.setPath(tempDir);
        KnowledgeBaseProperties kbProperties = new KnowledgeBasePropertiesImpl();
        EntityManager entityManager = testEntityManager.getEntityManager();
        testFixtures = new TestFixtures(testEntityManager);
        sut = new KnowledgeBaseServiceImpl(repoProps, kbProperties, entityManager);
        project = createProject(PROJECT_NAME);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        testEntityManager.clear();
        sut.destroy();
    }

    @Disabled("#1522 - GND tests not running")
    @ParameterizedTest(name = "{index}: reification {0}")
    @MethodSource("data")
    public void thatChildConceptsLabel(Reification reification) throws IOException
    {
        kb = buildRemoteKnowledgeBase(project, "GND", reification);
        String gndAccessURL = PROFILES.get("zbw-gnd").getAccess().getAccessUrl();
        TestFixtures.assumeEndpointIsAvailable(gndAccessURL);
        sut.registerKnowledgeBase(kb, sut.getRemoteConfig(gndAccessURL));

        long duration = System.currentTimeMillis();
        String concept = "http://d-nb.info/standards/elementset/gnd#Family";
        List<KBHandle> instanceKBHandle = sut.listInstances(kb, concept, true);
        duration = System.currentTimeMillis() - duration;

        LOG.info("Instances retrieved for {} : {}", concept, instanceKBHandle.size());
        LOG.info("Time required           : {} ms", duration);
        instanceKBHandle.stream().limit(10).forEach(h -> LOG.info("   {}", h));

        assertThat(instanceKBHandle).as("Check that instance list is not empty").isNotEmpty();
        assertThat(instanceKBHandle.stream().map(KBHandle::getName))
                .as("Check that child concept is retrieved").contains("Abele, Familie");
    }

    @Disabled("#1522 - GND tests not running")
    @ParameterizedTest(name = "{index}: reification {0}")
    @MethodSource("data")
    public void readInstance_ShouldReturnInstanceWithSubPropertyLabel(Reification reification)
        throws IOException
    {
        kb = buildRemoteKnowledgeBase(project, "GND", reification);
        String gndAccessURL = PROFILES.get("zbw-gnd").getAccess().getAccessUrl();
        TestFixtures.assumeEndpointIsAvailable(gndAccessURL);
        sut.registerKnowledgeBase(kb, sut.getRemoteConfig(gndAccessURL));

        String instanceId = "http://d-nb.info/gnd/7509336-4";
        Optional<KBInstance> instance = sut.readInstance(kb, instanceId);

        assertThat(instance).as("Check that instance is present").isPresent();
        assertThat(instance.get().getName()).as("Check that correct label is retrieved")
                .contains("Abingdon, Bettine");
    }

    @ParameterizedTest(name = "{index}: reification {0}")
    @MethodSource("data")
    public void readProperty_ShouldReturnPropertyWithSubPropertyLabel(Reification reification)
        throws IOException
    {
        kb = buildLocalKnowledgeBase(project, "Local", reification);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        KBProperty subpropertylabel = createSubPropertyLabel(kb);

        KBProperty property = testFixtures.buildProperty();
        // set name to null so that the subproperty label becomes the main label
        property.setName(null);
        sut.createProperty(kb, property);

        String labelLiteral = "Sub Property Label";
        createStmtWithLiteral(kb, KBHandle.of(property), subpropertylabel, labelLiteral);

        Optional<KBProperty> optProperty = sut.readProperty(kb, property.getIdentifier());
        assertThat(optProperty).as("Check that property is present").isPresent();
        assertThat(optProperty.get().getName()).as("Check that correct label is retrieved")
                .contains(labelLiteral);
    }

    // Helper

    private Project createProject(String name)
    {
        return testFixtures.createProject(name);
    }

    private KnowledgeBase buildRemoteKnowledgeBase(Project aProject, String aName,
            Reification aReification)
        throws IOException
    {
        PROFILES = readKnowledgeBaseProfiles();
        KnowledgeBase gnd = new KnowledgeBase();
        gnd.setProject(aProject);
        gnd.setName(aName);
        gnd.setType(RepositoryType.REMOTE);
        gnd.applyMapping(PROFILES.get("zbw-gnd").getMapping());
        gnd.applyRootConcepts(PROFILES.get("zbw-gnd"));
        gnd.setReification(aReification);
        gnd.setDefaultLanguage("en");
        gnd.setMaxResults(1000);

        return gnd;
    }

    private KnowledgeBase buildLocalKnowledgeBase(Project aProject, String aName,
            Reification aReification)
    {
        var local = new KnowledgeBase();
        local.setProject(aProject);
        local.setName(aName);
        local.setType(RepositoryType.LOCAL);
        local.applyMapping(new KnowledgeBaseMapping(OWL.CLASS.stringValue(),
                RDFS.SUBCLASSOF.stringValue(), RDF.TYPE.stringValue(),
                RDFS.SUBPROPERTYOF.stringValue(), RDFS.COMMENT.stringValue(),
                RDFS.LABEL.stringValue(), RDF.PROPERTY.stringValue(), RDFS.LABEL.stringValue(),
                RDFS.COMMENT.stringValue(), OWL.DEPRECATED.stringValue()));
        local.setReification(aReification);
        local.setDefaultLanguage("en");
        local.setMaxResults(1000);

        return local;
    }

    private KBProperty createSubPropertyLabel(KnowledgeBase aKB)
    {
        KBProperty subLabel = testFixtures.buildProperty();
        sut.createProperty(aKB, subLabel);

        KBProperty subPropertyHandle = new KBProperty(aKB.getSubPropertyIri());

        KBStatement subPropertyStmt = new KBStatement(null, subLabel.toKBHandle(),
                subPropertyHandle, aKB.getLabelIri());

        upsertStatement(aKB, subPropertyStmt);

        return subLabel;
    }

    private void createStmtWithLiteral(KnowledgeBase aKB, KBHandle aSubject, KBProperty aProperty,
            String aLiteral)
    {
        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        KBStatement stmt = new KBStatement(null, aSubject, aProperty, vf.createLiteral(aLiteral));
        upsertStatement(aKB, stmt);
    }

    private void upsertStatement(KnowledgeBase aKB, KBStatement aStatement)
    {
        // set reification to NONE just for "upserting" the statement, then restore old value
        Reification kbReification = kb.getReification();
        try {
            kb.setReification(Reification.NONE);
            sut.upsertStatement(aKB, aStatement);

        }
        finally {
            kb.setReification(kbReification);
        }
    }

    public static Map<String, KnowledgeBaseProfile> readKnowledgeBaseProfiles() throws IOException
    {
        return KnowledgeBaseProfile.readKnowledgeBaseProfiles();
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
