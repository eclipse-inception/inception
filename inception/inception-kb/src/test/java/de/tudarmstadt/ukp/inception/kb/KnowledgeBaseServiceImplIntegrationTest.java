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

import static de.tudarmstadt.ukp.inception.kb.reification.Reification.WIKIDATA;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.inception.documents.api.RepositoryPropertiesImpl;
import de.tudarmstadt.ukp.inception.kb.config.KnowledgeBasePropertiesImpl;
import de.tudarmstadt.ukp.inception.kb.graph.KBConcept;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.kb.graph.KBInstance;
import de.tudarmstadt.ukp.inception.kb.graph.KBObject;
import de.tudarmstadt.ukp.inception.kb.graph.KBProperty;
import de.tudarmstadt.ukp.inception.kb.graph.KBStatement;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryBuilder;
import de.tudarmstadt.ukp.inception.kb.reification.Reification;
import de.tudarmstadt.ukp.inception.kb.util.TestFixtures;
import de.tudarmstadt.ukp.inception.kb.yaml.KnowledgeBaseProfile;

@DataJpaTest( //
        showSql = false, //
        properties = { //
                "spring.main.banner-mode=off" })
@EnableAutoConfiguration
@EntityScan({ //
        "de.tudarmstadt.ukp.inception.kb.model", //
        "de.tudarmstadt.ukp.clarin.webanno.model" })
public class KnowledgeBaseServiceImplIntegrationTest
{
    private static final String STREAMS_NS = "http://mrklie.com/schemas/streams#";
    private static final String TAGSET_NS = "http://example.org/test/tagset#";
    private static final String OBO_NS = "http://purl.obolibrary.org/obo/";

    private static final String PROJECT_NAME = "Test project";
    private static final String KB_NAME = "Test knowledge base";

    private @TempDir File temporaryFolder;

    private @Autowired TestEntityManager testEntityManager;

    private KnowledgeBaseServiceImpl sut;
    private Project project;
    private KnowledgeBase kb;

    private TestFixtures testFixtures;

    public static Collection<Object[]> data()
    {
        return Arrays.stream(Reification.values()) //
                .map(r -> new Object[] { r }) //
                .collect(toList());
    }

    // @BeforeAll
    // public static void setUpOnce()
    // {
    // System.setProperty("org.eclipse.rdf4j.repository.debug", "true");
    // }

    public void setUp(Reification reification) throws Exception
    {
        setUp(reification, new KnowledgeBasePropertiesImpl());
    }

    public void setUp(Reification reification, KnowledgeBasePropertiesImpl aKbProperties)
        throws Exception
    {
        var repoProps = new RepositoryPropertiesImpl();
        repoProps.setPath(temporaryFolder);
        var entityManager = testEntityManager.getEntityManager();
        testFixtures = new TestFixtures(testEntityManager);
        sut = new KnowledgeBaseServiceImpl(repoProps, aKbProperties, entityManager);
        project = createProject(PROJECT_NAME);
        kb = buildKnowledgeBase(project, KB_NAME, reification);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        testEntityManager.clear();
        sut.destroy();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void registerKnowledgeBase_WithNewKnowledgeBase_ShouldSaveNewKnowledgeBase(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var savedKb = testEntityManager.find(KnowledgeBase.class, kb.getRepositoryId());
        assertThat(savedKb).as("Check that knowledge base was saved correctly")
                .hasFieldOrPropertyWithValue("name", KB_NAME) //
                .hasFieldOrPropertyWithValue("project", project) //
                .extracting("repositoryId") //
                .isNotNull();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void getKnowledgeBaseByName_IfExists_ShouldReturnKnowledgeBase(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var result = sut.getKnowledgeBaseByName(project, kb.getName());

        assertThat(result).isEqualTo(result);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void getKnowledgeBaseByName_IfAbsent_ShouldReturnNone(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var result = sut.getKnowledgeBaseByName(project, "Absent KB");

        assertThat(result).isEmpty();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void getKnowledgeBases_WithOneStoredKnowledgeBase_ShouldReturnStoredKnowledgeBase(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var knowledgeBases = sut.getKnowledgeBases(project);

        assertThat(knowledgeBases)
                .as("Check that only the previously created knowledge base is found").hasSize(1)
                .contains(kb);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void getKnowledgeBases_WithoutKnowledgeBases_ShouldReturnEmptyList(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var proj = createProject("Empty project");

        var knowledgeBases = sut.getKnowledgeBases(proj);

        assertThat(knowledgeBases).as("Check that no knowledge base is found").isEmpty();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void knowledgeBaseExists_WithExistingKnowledgeBase_ShouldReturnTrue(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThat(sut.knowledgeBaseExists(project, kb.getName()))
                .as("Check that knowledge base with given name already exists").isTrue();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void knowledgeBaseExists_WithNonexistentKnowledgeBase_ShouldReturnFalse(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        assertThat(sut.knowledgeBaseExists(project, kb.getName()))
                .as("Check that knowledge base with given name does not already exists").isFalse();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateKnowledgeBase_WithValidValues_ShouldUpdateKnowledgeBase(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        kb.setName("New name");
        kb.setClassIri(OWL.CLASS.stringValue());
        kb.setSubclassIri(OWL.NOTHING.stringValue());
        kb.setTypeIri(OWL.THING.stringValue());
        kb.setDescriptionIri(IriConstants.SCHEMA_DESCRIPTION.stringValue());
        kb.setLabelIri(RDFS.LITERAL.stringValue());
        kb.setPropertyTypeIri(OWL.OBJECTPROPERTY.stringValue());
        kb.setReadOnly(true);
        kb.setEnabled(false);
        kb.setBasePrefix("MyBasePrefix");

        var rootConcept1 = "http://www.ics.forth.gr/isl/CRMinf/I1_Argumentation";
        var rootConcept2 = "file:/data-to-load/07bde589-588c-4f0d-8715-c71c0ba2bfdb/crm-extensions/F10_Person";
        kb.setRootConcepts(asList(rootConcept1, rootConcept2));
        sut.updateKnowledgeBase(kb, sut.getNativeConfig());

        var savedKb = testEntityManager.find(KnowledgeBase.class, kb.getRepositoryId());
        assertThat(savedKb).as("Check that knowledge base was updated correctly")
                .hasFieldOrPropertyWithValue("name", "New name")
                .hasFieldOrPropertyWithValue("classIri", OWL.CLASS.stringValue())
                .hasFieldOrPropertyWithValue("subclassIri", OWL.NOTHING.stringValue())
                .hasFieldOrPropertyWithValue("typeIri", OWL.THING.stringValue())
                .hasFieldOrPropertyWithValue("descriptionIri",
                        IriConstants.SCHEMA_DESCRIPTION.stringValue())
                .hasFieldOrPropertyWithValue("name", "New name")
                .hasFieldOrPropertyWithValue("readOnly", true)
                .hasFieldOrPropertyWithValue("enabled", false)
                .hasFieldOrPropertyWithValue("labelIri", RDFS.LITERAL.stringValue())
                .hasFieldOrPropertyWithValue("propertyTypeIri", OWL.OBJECTPROPERTY.stringValue())
                .hasFieldOrPropertyWithValue("basePrefix", "MyBasePrefix")
                .hasFieldOrPropertyWithValue("rootConcepts",
                        new LinkedHashSet<>(asList(rootConcept1, rootConcept2)));
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateKnowledgeBase_WithUnregisteredKnowledgeBase_ShouldThrowIllegalStateException(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        assertThatExceptionOfType(IllegalStateException.class)
                .as("Check that updating knowledge base requires registration")
                .isThrownBy(() -> sut.updateKnowledgeBase(kb, sut.getNativeConfig()));
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void removeKnowledgeBase_WithStoredKnowledgeBase_ShouldDeleteKnowledgeBase(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        sut.removeKnowledgeBase(kb);

        var knowledgeBases = sut.getKnowledgeBases(project);
        assertThat(knowledgeBases).as("Check that the knowledge base has been deleted").hasSize(0);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void removeKnowledgeBase_WithUnregisteredKnowledgeBase_ShouldThrowIllegalStateException(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        assertThatExceptionOfType(IllegalStateException.class)
                .as("Check that updating knowledge base requires registration")
                .isThrownBy(() -> sut.removeKnowledgeBase(kb));
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void clear_WithNonemptyKnowledgeBase_ShouldDeleteAllCustomEntities(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createConcept(kb, buildConcept());
        sut.createProperty(kb, buildProperty());

        sut.clear(kb);

        var handles = new ArrayList<KBObject>();
        handles.addAll(sut.listAllConcepts(kb, false));
        handles.addAll(sut.listProperties(kb, false));
        assertThat(handles).as("Check that no custom entities are found after clearing").isEmpty();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void clear_WithNonemptyKnowledgeBase_ShouldNotDeleteImplicitEntities(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createConcept(kb, buildConcept());
        sut.createProperty(kb, buildProperty());

        sut.clear(kb);

        var handles = new ArrayList<KBObject>();
        handles.addAll(sut.listAllConcepts(kb, true));
        handles.addAll(sut.listProperties(kb, true));
        assertThat(handles)
                .as("Check that only entities with implicit namespace are found after clearing")
                .allMatch(this::hasImplicitNamespace);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void empty_WithEmptyKnowledgeBase_ShouldReturnTrue(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var isEmpty = sut.isEmpty(kb);

        assertThat(isEmpty).as("Check that knowledge base is empty").isTrue();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void empty_WithNonemptyKnowledgeBase_ShouldReturnFalse(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createConcept(kb, buildConcept());

        var isEmpty = sut.isEmpty(kb);

        assertThat(isEmpty).as("Check that knowledge base is not empty").isFalse();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void nonempty_WithEmptyKnowledgeBase_ShouldReturnTrue(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        sut.defineBaseProperties(kb);

        var listProperties = sut.listProperties(kb, true);
        var listIdentifier = listProperties.stream().map(KBObject::getIdentifier);
        String[] expectedProps = { kb.getSubclassIri(), kb.getLabelIri(), kb.getDescriptionIri(),
                kb.getTypeIri() };

        assertThat(listProperties) //
                .extracting(KBProperty::getIdentifier) //
                .containsExactly( //
                        "http://www.w3.org/2000/01/rdf-schema#comment",
                        "http://www.w3.org/2000/01/rdf-schema#label",
                        "http://www.w3.org/2000/01/rdf-schema#subClassOf",
                        "http://www.w3.org/2000/01/rdf-schema#subPropertyOf",
                        "http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        assertThat(listIdentifier) //
                .as("Check that base properties are created") //
                .contains(expectedProps);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void createConcept_WithEmptyIdentifier_ShouldCreateNewConcept(Reification reification)
        throws Exception
    {
        setUp(reification);

        var concept = buildConcept();

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createConcept(kb, concept);

        var savedConcept = sut.readConcept(kb, concept.getIdentifier(), true).get();
        assertThat(savedConcept).as("Check that concept was saved correctly")
                .hasFieldOrPropertyWithValue("description", concept.getDescription())
                .hasFieldOrPropertyWithValue("name", concept.getName());
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void createConcept_WithCustomBasePrefix_ShouldCreateNewConceptWithCustomPrefix(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var concept = buildConcept();

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        String customPrefix = "http://www.ukp.informatik.tu-darmstadt.de/customPrefix#";
        kb.setBasePrefix(customPrefix);
        sut.createConcept(kb, concept);

        var savedConcept = sut.readConcept(kb, concept.getIdentifier(), true).get();
        assertThat(savedConcept).as("Check that concept was saved correctly")
                .hasFieldOrPropertyWithValue("description", concept.getDescription())
                .hasFieldOrPropertyWithValue("name", concept.getName());

        var id = savedConcept.getIdentifier();
        var savedConceptPrefix = id.substring(0, id.lastIndexOf("#") + 1);
        assertThat(savedConceptPrefix).isEqualTo(customPrefix);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void createConcept_WithNonemptyIdentifier_ShouldThrowIllegalArgumentException(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var concept = new KBConcept();
        concept.setIdentifier("Nonempty Identifier");

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatIllegalArgumentException()
                .as("Check that creating a concept requires empty identifier")
                .isThrownBy(() -> sut.createConcept(kb, concept))
                .withMessage("Identifier must be empty on create");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void createConcept_WithReadOnlyKnowledgeBase_ShouldDoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        var concept = buildConcept();
        kb.setReadOnly(true);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatExceptionOfType(ReadOnlyException.class)
                .isThrownBy(() -> sut.createConcept(kb, concept));
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readConcept_WithExistingConcept_ShouldReturnSavedConcept(Reification reification)
        throws Exception
    {
        setUp(reification);

        var concept = buildConcept();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createConcept(kb, concept);

        var savedConcept = sut.readConcept(kb, concept.getIdentifier(), true).get();

        assertThat(savedConcept).as("Check that concept was read correctly")
                .hasFieldOrPropertyWithValue("description", concept.getDescription())
                .hasFieldOrPropertyWithValue("name", concept.getName());
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readConcept_WithNonexistentConcept_ShouldReturnEmptyResult(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var savedConcept = sut.readConcept(kb, "https://nonexistent.identifier.test", true);

        assertThat(savedConcept.isPresent()).as("Check that no concept was read").isFalse();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateConcept_WithAlteredConcept_ShouldUpdateConcept(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        var concept = buildConcept();
        sut.createConcept(kb, concept);

        concept.setDescription("New description");
        concept.setName("New name");
        sut.updateConcept(kb, concept);

        var savedConcept = sut.readConcept(kb, concept.getIdentifier(), true).get();
        assertThat(savedConcept).as("Check that concept was updated correctly")
                .hasFieldOrPropertyWithValue("description", "New description")
                .hasFieldOrPropertyWithValue("name", "New name");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    // TODO: Check whether this is a feature or not
    public void updateConcept_WithNonexistentConcept_ShouldCreateConcept(Reification reification)
        throws Exception
    {
        setUp(reification);

        var concept = buildConcept();
        concept.setIdentifier("https://nonexistent.identifier.test");
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        sut.updateConcept(kb, concept);

        var savedConcept = sut.readConcept(kb, "https://nonexistent.identifier.test", true).get();
        assertThat(savedConcept)
                .hasFieldOrPropertyWithValue("description", concept.getDescription())
                .hasFieldOrPropertyWithValue("name", concept.getName());
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateConcept_WithConceptWithBlankIdentifier_ShouldThrowIllegalArgumentException(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var concept = buildConcept();
        concept.setIdentifier("");
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatIllegalArgumentException()
                .as("Check that updating a concept requires nonempty identifier")
                .isThrownBy(() -> sut.updateConcept(kb, concept))
                .withMessage("Identifier cannot be empty on update");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateConcept_WithConceptWithNullIdentifier_ShouldThrowIllegalArgumentException(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var concept = buildConcept();
        concept.setIdentifier(null);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatIllegalArgumentException()
                .as("Check that updating a concept requires non-null identifier")
                .isThrownBy(() -> sut.updateConcept(kb, concept))
                .withMessage("Identifier cannot be empty on update");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateConcept_WithReadOnlyKnowledgeBase_ShouldDoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        var concept = buildConcept();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createConcept(kb, concept);
        setReadOnly(kb);

        concept.setDescription("New description");
        concept.setName("New name");

        assertThatExceptionOfType(ReadOnlyException.class)
                .isThrownBy(() -> sut.updateConcept(kb, concept));

        var savedConcept = sut.readConcept(kb, concept.getIdentifier(), true).get();
        assertThat(savedConcept).as("Check that concept has not been updated")
                .hasFieldOrPropertyWithValue("description", "Concept description")
                .hasFieldOrPropertyWithValue("name", "Concept name");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteConcept_WithConceptReferencedAsObject_ShouldDeleteConceptAndStatement(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();
        var property = buildProperty();
        var concept = buildConcept();

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createInstance(kb, instance);
        sut.createProperty(kb, property);
        sut.createConcept(kb, concept);

        sut.upsertStatement(kb,
                buildStatement(kb, instance.toKBHandle(), property, concept.getIdentifier()));

        sut.deleteConcept(kb, concept);

        assertThat(sut.listStatementsWithPredicateOrObjectReference(kb, concept.getIdentifier()))
                .isEmpty();

        var savedConcept = sut.readConcept(kb, concept.getIdentifier(), true);
        assertThat(savedConcept.isPresent()).as("Check that concept was not found after delete")
                .isFalse();

    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteConcept_WithExistingConcept_ShouldDeleteConcept(Reification reification)
        throws Exception
    {
        setUp(reification);

        var concept = buildConcept();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createConcept(kb, concept);

        sut.deleteConcept(kb, concept);

        var savedConcept = sut.readConcept(kb, concept.getIdentifier(), true);
        assertThat(savedConcept.isPresent()).as("Check that concept was not found after delete")
                .isFalse();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteConcept_WithNonexistentConcept_ShouldNoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        var concept = buildConcept();
        concept.setIdentifier("https://nonexistent.identifier.test");
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatCode(() -> sut.deleteConcept(kb, concept))
                .as("Check that deleting non-existent concept does nothing")
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteConcept_WithReadOnlyKnowledgeBase_ShouldDoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        var concept = buildConcept();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createConcept(kb, concept);
        setReadOnly(kb);

        assertThatExceptionOfType(ReadOnlyException.class)
                .isThrownBy(() -> sut.deleteConcept(kb, concept));

        var savedConcept = sut.readConcept(kb, concept.getIdentifier(), true);
        assertThat(savedConcept.isPresent()).as("Check that concept was not deleted").isTrue();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void listConcepts_WithASavedConceptAndNotAll_ShouldFindOneConcept(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var concept = buildConcept();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createConcept(kb, concept);

        var concepts = sut.listAllConcepts(kb, false);

        assertThat(concepts).as("Check that concepts contain the one, saved item") //
                .hasSize(1) //
                .element(0).hasFieldOrPropertyWithValue("identifier", concept.getIdentifier())
                .hasFieldOrProperty("name")
                .matches(h -> h.getIdentifier().startsWith(IriConstants.INCEPTION_NAMESPACE));
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void listConcepts_WithNoSavedConceptAndAll_ShouldFindRdfConcepts(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var concepts = sut.listAllConcepts(kb, true);

        assertThat(concepts).as("Check that all concepts have implicit namespaces")
                .allMatch(this::hasImplicitNamespace);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void createProperty_WithEmptyIdentifier_ShouldCreateNewProperty(Reification reification)
        throws Exception
    {
        setUp(reification);

        var property = buildProperty();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        sut.createProperty(kb, property);

        var savedProperty = sut.readProperty(kb, property.getIdentifier()).get();
        assertThat(savedProperty).as("Check that property was created correctly")
                .hasNoNullFieldsOrPropertiesExcept("language")
                .hasFieldOrPropertyWithValue("description", property.getDescription())
                .hasFieldOrPropertyWithValue("domain", property.getDomain())
                .hasFieldOrPropertyWithValue("name", property.getName())
                .hasFieldOrPropertyWithValue("range", property.getRange());
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void createProperty_WithCustomBasePrefix_ShouldCreateNewPropertyWithCustomPrefix(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        assumeFalse(WIKIDATA.equals(kb.getReification()),
                "Wikidata reification has hardcoded property prefix");

        var property = buildProperty();

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        var customPrefix = "http://www.ukp.informatik.tu-darmstadt.de/customPrefix#";
        kb.setBasePrefix(customPrefix);
        sut.createProperty(kb, property);

        var savedProperty = sut.readProperty(kb, property.getIdentifier()).get();
        assertThat(savedProperty).as("Check that property was saved correctly")
                .hasFieldOrPropertyWithValue("description", property.getDescription())
                .hasFieldOrPropertyWithValue("name", property.getName());

        var id = savedProperty.getIdentifier();
        var savedPropertyPrefix = id.substring(0, id.lastIndexOf("#") + 1);
        assertThat(savedPropertyPrefix).isEqualTo(customPrefix);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void createProperty_WithNonemptyIdentifier_ShouldThrowIllegalArgumentException(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var property = buildProperty();
        property.setIdentifier("Nonempty Identifier");

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatIllegalArgumentException()
                .as("Check that creating a property requires empty identifier")
                .isThrownBy(() -> sut.createProperty(kb, property))
                .withMessage("Identifier must be empty on create");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void createProperty_WithReadOnlyKnowledgeBase_ShouldDoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        var property = buildProperty();
        kb.setReadOnly(true);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatExceptionOfType(ReadOnlyException.class)
                .isThrownBy(() -> sut.createProperty(kb, property));
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readProperty_WithExistingConcept_ShouldReturnSavedProperty(Reification reification)
        throws Exception
    {
        setUp(reification);

        var property = buildProperty();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createProperty(kb, property);

        var savedProperty = sut.readProperty(kb, property.getIdentifier()).get();

        assertThat(savedProperty).as("Check that property was saved correctly")
                .hasNoNullFieldsOrPropertiesExcept("language")
                .hasFieldOrPropertyWithValue("description", property.getDescription())
                .hasFieldOrPropertyWithValue("domain", property.getDomain())
                .hasFieldOrPropertyWithValue("name", property.getName())
                .hasFieldOrPropertyWithValue("range", property.getRange());
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readProperty_WithNonexistentProperty_ShouldReturnEmptyResult(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var savedProperty = sut.readProperty(kb, "https://nonexistent.identifier.test");

        assertThat(savedProperty.isPresent()).as("Check that no property was read").isFalse();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateProperty_WithAlteredProperty_ShouldUpdateProperty(Reification reification)
        throws Exception
    {
        setUp(reification);

        var property = buildProperty();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createProperty(kb, property);

        property.setDescription("New property description");
        property.setDomain("https://new.schema.com/#domain");
        property.setName("New property name");
        property.setRange("https://new.schema.com/#range");
        sut.updateProperty(kb, property);

        var savedProperty = sut.readProperty(kb, property.getIdentifier()).get();
        assertThat(savedProperty).as("Check that property was updated correctly")
                .hasFieldOrPropertyWithValue("description", property.getDescription())
                .hasFieldOrPropertyWithValue("domain", property.getDomain())
                .hasFieldOrPropertyWithValue("name", property.getName())
                .hasFieldOrPropertyWithValue("range", property.getRange());
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    // TODO: Check whether this is a feature or not
    public void updateProperty_WithNonexistentProperty_ShouldCreateProperty(Reification reification)
        throws Exception
    {
        setUp(reification);

        var property = buildProperty();
        property.setIdentifier("https://nonexistent.identifier.test");
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        sut.updateProperty(kb, property);

        var savedProperty = sut.readProperty(kb, "https://nonexistent.identifier.test").get();
        assertThat(savedProperty).as("Check that property was updated correctly")
                .hasFieldOrPropertyWithValue("description", property.getDescription())
                .hasFieldOrPropertyWithValue("domain", property.getDomain())
                .hasFieldOrPropertyWithValue("name", property.getName())
                .hasFieldOrPropertyWithValue("range", property.getRange());
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateProperty_WithPropertyWithBlankIdentifier_ShouldThrowIllegalArgumentException(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var property = buildProperty();
        property.setIdentifier("");
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatIllegalArgumentException()
                .as("Check that updating a property requires nonempty identifier")
                .isThrownBy(() -> sut.updateProperty(kb, property))
                .withMessage("Identifier cannot be empty on update");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateProperty_WithPropertyWithNullIdentifier_ShouldThrowIllegalArgumentException(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var property = buildProperty();
        property.setIdentifier(null);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatIllegalArgumentException()
                .as("Check that updating a property requires nonempty identifier")
                .isThrownBy(() -> sut.updateProperty(kb, property))
                .withMessage("Identifier cannot be empty on update");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateProperty_WithReadOnlyKnowledgeBase_ShouldDoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        var property = buildProperty();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createProperty(kb, property);
        setReadOnly(kb);

        property.setDescription("New property description");
        property.setDomain("https://new.schema.com/#domain");
        property.setName("New property name");
        property.setRange("https://new.schema.com/#range");

        assertThatExceptionOfType(ReadOnlyException.class)
                .isThrownBy(() -> sut.updateProperty(kb, property));

        var savedProperty = sut.readProperty(kb, property.getIdentifier()).get();
        assertThat(savedProperty).as("Check that property has not been updated")
                .hasFieldOrPropertyWithValue("description", "Property description")
                .hasFieldOrPropertyWithValue("domain", "https://test.schema.com/#domain")
                .hasFieldOrPropertyWithValue("name", "Property name")
                .hasFieldOrPropertyWithValue("range", "https://test.schema.com/#range");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteProperty_WithExistingProperty_ShouldDeleteProperty(Reification reification)
        throws Exception
    {
        setUp(reification);

        var property = buildProperty();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createProperty(kb, property);

        sut.deleteProperty(kb, property);

        var savedProperty = sut.readProperty(kb, property.getIdentifier());
        assertThat(savedProperty.isPresent()).as("Check that property was not found after delete")
                .isFalse();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteProperty_WithNotExistingProperty_ShouldNoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        var property = buildProperty();
        property.setIdentifier("https://nonexistent.identifier.test");
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatCode(() -> sut.deleteProperty(kb, property))
                .as("Check that deleting non-existent property does nothing")
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteProperty_WithReadOnlyKnowledgeBase_ShouldNoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        var property = buildProperty();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createProperty(kb, property);
        setReadOnly(kb);

        assertThatExceptionOfType(ReadOnlyException.class)
                .isThrownBy(() -> sut.deleteProperty(kb, property));

        var savedProperty = sut.readProperty(kb, property.getIdentifier());
        assertThat(savedProperty.isPresent()).as("Check that property was not deleted").isTrue();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void listProperties_WithASavedConceptAndNotAll_ShouldFindOneConcept(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var property = buildProperty();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createProperty(kb, property);

        var properties = sut.listProperties(kb, false);

        assertThat(properties).as("Check that properties contain the one, saved item").hasSize(1)
                .element(0).hasFieldOrPropertyWithValue("identifier", property.getIdentifier())
                .hasFieldOrProperty("name");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void listProperties_WithNoSavedConceptAndAll_ShouldFindRdfConcepts(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var properties = sut.listProperties(kb, true);

        assertThat(properties).as("Check that all properties have implicit namespaces")
                .allMatch(this::hasImplicitNamespace);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void createInstance_WithEmptyIdentifier_ShouldCreateNewInstance(Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createInstance(kb, instance);

        var savedInstance = sut.readInstance(kb, instance.getIdentifier()).get();
        assertThat(savedInstance).as("Check that instance was saved correctly")
                .hasFieldOrPropertyWithValue("description", instance.getDescription())
                .hasFieldOrPropertyWithValue("name", instance.getName());
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void createInstance_WithCustomBasePrefix_ShouldCreateNewInstanceWithCustomPrefix(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        var customPrefix = "http://www.ukp.informatik.tu-darmstadt.de/customPrefix#";
        kb.setBasePrefix(customPrefix);
        sut.createInstance(kb, instance);

        var savedInstance = sut.readInstance(kb, instance.getIdentifier()).get();
        assertThat(savedInstance).as("Check that Instance was saved correctly")
                .hasFieldOrPropertyWithValue("description", instance.getDescription())
                .hasFieldOrPropertyWithValue("name", instance.getName());

        var id = savedInstance.getIdentifier();
        var savedInstancePrefix = id.substring(0, id.lastIndexOf("#") + 1);
        assertThat(savedInstancePrefix).isEqualTo(customPrefix);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void createInstance_WithNonemptyIdentifier_ShouldThrowIllegalArgumentException(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = new KBInstance();
        instance.setIdentifier("Nonempty Identifier");

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatIllegalArgumentException()
                .as("Check that creating a instance requires empty identifier")
                .isThrownBy(() -> sut.createInstance(kb, instance))
                .withMessage("Identifier must be empty on create");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void createInstance_WithReadOnlyKnowledgeBase_ShouldDoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();
        kb.setReadOnly(true);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatExceptionOfType(ReadOnlyException.class)
                .isThrownBy(() -> sut.createInstance(kb, instance));
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readInstance_WithExistingInstance_ShouldReturnSavedInstance(Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createInstance(kb, instance);

        var savedInstance = sut.readInstance(kb, instance.getIdentifier()).get();

        assertThat(savedInstance).as("Check that instance was read correctly")
                .hasFieldOrPropertyWithValue("description", instance.getDescription())
                .hasFieldOrPropertyWithValue("name", instance.getName());
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readInstance_WithNonexistentInstance_ShouldReturnEmptyResult(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var savedInstance = sut.readInstance(kb, "https://nonexistent.identifier.test");

        assertThat(savedInstance.isPresent()).as("Check that no instance was read").isFalse();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateInstance_WithAlteredInstance_ShouldUpdateInstance(Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createInstance(kb, instance);

        instance.setDescription("New description");
        instance.setName("New name");
        sut.updateInstance(kb, instance);

        var savedInstance = sut.readInstance(kb, instance.getIdentifier()).get();
        assertThat(savedInstance).as("Check that instance was updated correctly")
                .hasFieldOrPropertyWithValue("description", "New description")
                .hasFieldOrPropertyWithValue("name", "New name");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    // TODO: Check whether this is a feature or not
    public void updateInstance_WithNonexistentInstance_ShouldCreateInstance(Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();
        instance.setIdentifier("https://nonexistent.identifier.test");
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        sut.updateInstance(kb, instance);

        var savedInstance = sut.readInstance(kb, "https://nonexistent.identifier.test").get();
        assertThat(savedInstance)
                .hasFieldOrPropertyWithValue("description", instance.getDescription())
                .hasFieldOrPropertyWithValue("name", instance.getName());
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateInstance_WithInstanceWithBlankIdentifier_ShouldThrowIllegalArgumentException(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();
        instance.setIdentifier("");
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatIllegalArgumentException()
                .as("Check that updating a instance requires nonempty identifier")
                .isThrownBy(() -> sut.updateInstance(kb, instance))
                .withMessage("Identifier cannot be empty on update");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateInstance_WithInstanceWithNullIdentifier_ShouldThrowIllegalArgumentException(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();
        instance.setIdentifier(null);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatIllegalArgumentException()
                .as("Check that updating a instance requires non-null identifier")
                .isThrownBy(() -> sut.updateInstance(kb, instance))
                .withMessage("Identifier cannot be empty on update");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void updateInstance_WithReadOnlyKnowledgeBase_ShouldDoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createInstance(kb, instance);
        setReadOnly(kb);

        instance.setDescription("New description");
        instance.setName("New name");

        assertThatExceptionOfType(ReadOnlyException.class)
                .isThrownBy(() -> sut.updateInstance(kb, instance));

        var savedInstance = sut.readInstance(kb, instance.getIdentifier()).get();
        assertThat(savedInstance).as("Check that instance has not been updated")
                .hasFieldOrPropertyWithValue("description", "Instance description")
                .hasFieldOrPropertyWithValue("name", "Instance name");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteInstance_WithExistingInstance_ShouldDeleteInstance(Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createInstance(kb, instance);

        sut.deleteInstance(kb, instance);

        var savedInstance = sut.readInstance(kb, instance.getIdentifier());
        assertThat(savedInstance.isPresent()).as("Check that instance was not found after delete")
                .isFalse();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteInstance_WithInstanceReferencedAsObject_ShouldDeleteInstanceAndStatement(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();
        var property = buildProperty();
        var instance2 = buildInstance();

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createInstance(kb, instance);
        sut.createProperty(kb, property);
        sut.createInstance(kb, instance2);

        sut.upsertStatement(kb,
                buildStatement(kb, instance.toKBHandle(), property, instance2.getIdentifier()));

        sut.deleteInstance(kb, instance2);

        assertThat(sut.listStatementsWithPredicateOrObjectReference(kb, instance2.getIdentifier()))
                .isEmpty();

        var savedInstance = sut.readInstance(kb, instance2.getIdentifier());
        assertThat(savedInstance.isPresent()).as("Check that Instance was not found after delete")
                .isFalse();

    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteInstance_WithNonexistentProperty_ShouldNoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();
        instance.setIdentifier("https://nonexistent.identifier.test");
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThatCode(() -> sut.deleteInstance(kb, instance))
                .as("Check that deleting non-existent instance does nothing")
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteInstance_WithReadOnlyKnowledgeBase_ShouldNoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        var instance = buildInstance();
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createInstance(kb, instance);
        setReadOnly(kb);

        assertThatExceptionOfType(ReadOnlyException.class)
                .isThrownBy(() -> sut.deleteInstance(kb, instance));

        var savedInstance = sut.readInstance(kb, instance.getIdentifier());
        assertThat(savedInstance.isPresent()).as("Check that instance was not deleted").isTrue();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void listInstances_WithASavedInstanceAndNotAll_ShouldFindOneInstance(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        var concept = buildConcept();
        var instance = buildInstance();
        sut.createConcept(kb, concept);
        instance.setType(URI.create(concept.getIdentifier()));
        sut.createInstance(kb, instance);

        var instances = sut.listInstances(kb, concept.getIdentifier(), false);

        assertThat(instances).as("Check that instances contain the one, saved item").hasSize(1)
                .element(0).hasFieldOrPropertyWithValue("identifier", instance.getIdentifier())
                .hasFieldOrProperty("name")
                .matches(h -> h.getIdentifier().startsWith(IriConstants.INCEPTION_NAMESPACE));
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void upsertStatement_WithUnsavedStatement_ShouldCreateStatement(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        var concept = buildConcept();
        var property = buildProperty();
        sut.createConcept(kb, concept);
        sut.createProperty(kb, property);
        var statement = buildStatement(kb, concept.toKBHandle(), property, "Test statement");

        sut.upsertStatement(kb, statement);

        var statements = sut.listStatements(kb, concept.toKBHandle(), false);
        assertThat(statements).as("Check that the statement was saved correctly")
                .filteredOn(this::isNotAbstractNorClosedStatement).hasSize(1).element(0)
                .hasFieldOrProperty("instance").hasFieldOrProperty("property")
                .hasFieldOrPropertyWithValue("value", "Test statement")
                .hasFieldOrPropertyWithValue("inferred", false);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void upsertStatement_WithExistingStatement_ShouldUpdateStatement(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        var concept = buildConcept();
        var property = buildProperty();
        sut.createConcept(kb, concept);
        sut.createProperty(kb, property);
        var statement = buildStatement(kb, concept.toKBHandle(), property, "Test statement");
        sut.upsertStatement(kb, statement);

        statement.setValue("Altered test property");
        sut.upsertStatement(kb, statement);

        var statements = sut.listStatements(kb, concept.toKBHandle(), false);
        assertThat(statements).as("Check that the statement was updated correctly")
                .filteredOn(this::isNotAbstractNorClosedStatement).hasSize(1).element(0)
                .hasFieldOrProperty("instance").hasFieldOrProperty("property")
                .hasFieldOrPropertyWithValue("value", "Altered test property")
                .hasFieldOrPropertyWithValue("inferred", false);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void upsertStatement_WithReadOnlyKnowledgeBase_ShouldDoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        var concept = buildConcept();
        var property = buildProperty();
        sut.createConcept(kb, concept);
        sut.createProperty(kb, property);
        var statement = buildStatement(kb, concept.toKBHandle(), property, "Test statement");
        setReadOnly(kb);

        var statementCountBeforeUpsert = sut.listStatements(kb, concept.toKBHandle(), false).size();
        assertThatExceptionOfType(ReadOnlyException.class)
                .isThrownBy(() -> sut.upsertStatement(kb, statement));

        var statementCountAfterUpsert = sut.listStatements(kb, concept.toKBHandle(), false).size();
        assertThat(statementCountBeforeUpsert).as("Check that statement was not created")
                .isEqualTo(statementCountAfterUpsert);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteStatement_WithExistingStatement_ShouldDeleteStatement(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        var concept = buildConcept();
        var property = buildProperty();
        sut.createConcept(kb, concept);
        sut.createProperty(kb, property);
        var statement = buildStatement(kb, concept.toKBHandle(), property, "Test statement");
        sut.upsertStatement(kb, statement);

        sut.deleteStatement(kb, statement);

        var statements = sut.listStatements(kb, concept.toKBHandle(), false);
        assertThat(statements).as("Check that the statement was deleted correctly")
                .noneMatch(stmt -> "Test statement".equals(stmt.getValue()));
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteStatement_WithNonExistentStatement_ShouldDoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        var concept = buildConcept();
        var property = buildProperty();
        sut.createConcept(kb, concept);
        sut.createProperty(kb, property);
        var statement = buildStatement(kb, concept.toKBHandle(), property, "Test statement");

        assertThatCode(() -> sut.deleteStatement(kb, statement)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void deleteStatement_WithReadOnlyKnowledgeBase_ShouldDoNothing(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        var concept = buildConcept();
        var property = buildProperty();
        sut.createConcept(kb, concept);
        sut.createProperty(kb, property);
        var statement = buildStatement(kb, concept.toKBHandle(), property, "Test statement");
        sut.upsertStatement(kb, statement);
        setReadOnly(kb);

        var statementCountBeforeDeletion = sut.listStatements(kb, concept.toKBHandle(), false)
                .size();

        assertThatExceptionOfType(ReadOnlyException.class)
                .isThrownBy(() -> sut.deleteStatement(kb, statement));

        var statementCountAfterDeletion = sut.listStatements(kb, concept.toKBHandle(), false)
                .size();
        assertThat(statementCountAfterDeletion).as("Check that statement was not deleted")
                .isEqualTo(statementCountBeforeDeletion);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void listStatements_WithExistentStatementAndNotAll_ShouldReturnOnlyThisStatement(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        var concept = buildConcept();
        var property = buildProperty();
        sut.createConcept(kb, concept);
        sut.createProperty(kb, property);
        var statement = buildStatement(kb, concept.toKBHandle(), property, "Test statement");
        sut.upsertStatement(kb, statement);

        var statements = sut.listStatements(kb, concept.toKBHandle(), false);

        assertThat(statements).as("Check that saved statement is found")
                .filteredOn(this::isNotAbstractNorClosedStatement).hasSize(1).element(0)
                .hasFieldOrPropertyWithValue("value", "Test statement");

        assertThat(statements.get(0).getOriginalTriples())
                .as("Check that original statements are recreated")
                .containsExactlyInAnyOrderElementsOf(statement.getOriginalTriples());
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void listStatements_WithNonexistentStatementAndAll_ShouldRetuenAllStatements(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        KBConcept concept = buildConcept();
        sut.createConcept(kb, concept);

        List<KBStatement> statements = sut.listStatements(kb, concept.toKBHandle(), true);

        assertThat(statements).filteredOn(this::isNotAbstractNorClosedStatement)
                .as("Check that all statements have implicit namespace")
                .allMatch(stmt -> hasImplicitNamespace(stmt.getProperty()));
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void getConceptRoots_WithLibraryOntology_ShouldReturnRootConcepts(
            Reification reification)
        throws Exception
    {
        setUp(reification);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        importKnowledgeBase("data/library_ontology.ttl");
        setSchema(kb, OWL.CLASS, RDFS.SUBCLASSOF, RDF.TYPE, RDFS.COMMENT, RDFS.LABEL, RDF.PROPERTY);

        var rootConcepts = sut.listRootConcepts(kb, false);

        // The external Collection class is only used as a superclass and has no label
        assertThat(rootConcepts) //
                .as("Check that all root concepts have been found")
                .usingRecursiveFieldByFieldElementComparatorOnFields("identifier", "name")
                .containsExactlyInAnyOrder(
                        new KBHandle("http://example.org/test/library#Agent", "Agent"),
                        new KBHandle("http://example.org/test/library#Work", "Work"),
                        new KBHandle("http://example.org/test/library#Place", "Place"),
                        new KBHandle("http://example.org/test/library#Subject", "Subject"),
                        new KBHandle("http://example.org/test/external#Collection", null));
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void getConceptRoots_WithLibraryOntologyAndExplicitlyDefinedConcepts_ShouldReturnRootConcepts(
            Reification reification)
        throws Exception
    {
        setUp(reification);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        kb.setDefaultLanguage("en");
        kb.setRootConcepts(asList("http://example.org/test/library#Agent",
                "http://example.org/test/library#Place"));
        sut.updateKnowledgeBase(kb);
        importKnowledgeBase("data/library_ontology.ttl");
        setSchema(kb, OWL.CLASS, RDFS.SUBCLASSOF, RDF.TYPE, RDFS.COMMENT, RDFS.LABEL, RDF.PROPERTY);

        var rootConcepts = sut.listRootConcepts(kb, false).stream().map(KBHandle::getName);

        assertThat(rootConcepts).as("Check that only the explicitly defined roots have been found")
                .containsExactlyInAnyOrder("Agent", "Place");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void getConceptRoots_WithStreams_ReturnsOnlyRootConcepts(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        importKnowledgeBase("data/streams.ttl");
        setSchema(kb, RDFS.CLASS, RDFS.SUBCLASSOF, RDF.TYPE, RDFS.COMMENT, RDFS.LABEL,
                RDF.PROPERTY);

        // The data also contains instances and an implicitly declared subclass, neither of
        // which may show up as a root
        var rootConcepts = sut.listRootConcepts(kb, false).stream().map(KBHandle::getName);

        assertThat(rootConcepts).as("Check that only root concepts have been found")
                .containsExactlyInAnyOrder("InputStream", "OutputStream");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void getChildConcepts_WithStreams_ReturnsOnlyImmediateChildren(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        importKnowledgeBase("data/streams.ttl");
        KBConcept concept = sut.readConcept(kb, "http://mrklie.com/schemas/streams#input", true)
                .get();
        setSchema(kb, RDFS.CLASS, RDFS.SUBCLASSOF, RDF.TYPE, RDFS.COMMENT, RDFS.LABEL,
                RDF.PROPERTY);

        Stream<String> childConcepts = sut.listChildConcepts(kb, concept.getIdentifier(), false)
                .stream().map(KBHandle::getName);

        String[] expectedLabels = { "ByteArrayInputStream", "FileInputStream", "FilterInputStream",
                "ObjectInputStream", "PipedInputStream", "SequenceInputStream",
                "StringBufferInputStream" };
        assertThat(childConcepts).as("Check that all immediate child concepts have been found")
                .containsExactlyInAnyOrder(expectedLabels);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void getChildConcepts_WithStreams_IncludesImplicitClassesButNoInstances(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        importKnowledgeBase("data/streams.ttl");
        setSchema(kb, RDFS.CLASS, RDFS.SUBCLASSOF, RDF.TYPE, RDFS.COMMENT, RDFS.LABEL,
                RDF.PROPERTY);

        assertThat(sut.listChildConcepts(kb, STREAMS_NS + "filteroutput", false)) //
                .as("Check that a class declared only implicitly is a child") //
                .extracting(KBHandle::getName) //
                .containsExactly("CheckedOutputStream");
        assertThat(sut.listChildConcepts(kb, STREAMS_NS + "checkedoutput", false)) //
                .extracting(KBHandle::getName) //
                .containsExactly("VerifiedOutputStream");
        assertThat(sut.listChildConcepts(kb, STREAMS_NS + "bufferedinput", false)) //
                .as("Check that an instance is not a child concept") //
                .isEmpty();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void getParentConceptList_WithStreams_ReturnsAllAncestors(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        importKnowledgeBase("data/streams.ttl");
        setSchema(kb, RDFS.CLASS, RDFS.SUBCLASSOF, RDF.TYPE, RDFS.COMMENT, RDFS.LABEL,
                RDF.PROPERTY);

        var parents = sut
                .getParentConceptList(kb, "http://mrklie.com/schemas/streams#bufferedinput", false)
                .stream().map(KBHandle::getName);
        assertThat(parents).as("Check that the direct and the indirect parent have been found")
                .containsExactlyInAnyOrder("FilterInputStream", "InputStream");

        var rootParents = sut.getParentConceptList(kb, "http://mrklie.com/schemas/streams#input",
                false);
        assertThat(rootParents).as("Check that a root concept has no parents").isEmpty();

        var instanceParents = sut.getParentConceptList(kb, STREAMS_NS + "systemin", false).stream()
                .map(KBHandle::getName);
        assertThat(instanceParents).as("Check that the classes of an instance are its parents")
                .containsExactlyInAnyOrder("BufferedInputStream", "FilterInputStream",
                        "InputStream");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void getEnabledKnowledgeBases_WithOneEnabledOneDisabled_ReturnsOnlyEnabledKB(
            Reification reification)
        throws Exception

    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        KnowledgeBase kb2 = buildKnowledgeBase(project, "TestKB2", reification);
        kb2.setEnabled(false);
        sut.registerKnowledgeBase(kb2, sut.getNativeConfig());

        List<KnowledgeBase> enabledKBs = sut.getEnabledKnowledgeBases(project);

        assertThat(enabledKBs).as("Check that only the enabled KB (kb) is in this list")
                .contains(kb).hasSize(1);

    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void getEnabledKnowledgeBases_WithoutEnabledKnowledgeBases_ShouldReturnEmptyList(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        kb.setEnabled(false);
        sut.updateKnowledgeBase(kb, sut.getNativeConfig());

        List<KnowledgeBase> knowledgeBases = sut.getEnabledKnowledgeBases(project);

        assertThat(knowledgeBases).as("Check that the list is empty").isEmpty();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void listStatementsWithPredicateOrObjectReference_WithExistingStatements_ShouldOnlyReturnStatementsWhereIdIsPredOrObj(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        KBInstance subject = buildInstance();
        KBInstance object = buildInstance();
        KBProperty property = buildProperty();

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        sut.createInstance(kb, subject);
        sut.createProperty(kb, property);
        sut.createInstance(kb, object);

        KBStatement stmt1 = buildStatement(kb, subject.toKBHandle(), property,
                object.getIdentifier());

        sut.upsertStatement(kb, stmt1);
        List<Statement> result = sut.listStatementsWithPredicateOrObjectReference(kb,
                object.getIdentifier());
        assertThat(result).allMatch(new Predicate<Statement>()
        {

            @Override
            public boolean test(Statement arg0)
            {
                return arg0.getObject().stringValue().equals(object.getIdentifier());
            }

        });
        assertThat(result.size()).isGreaterThanOrEqualTo(1);

    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void thatExistsFindsExistingStatement(Reification reification) throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        KBConcept concept = buildConcept();
        KBProperty property = buildProperty();
        sut.createConcept(kb, concept);
        sut.createProperty(kb, property);
        KBStatement statement = buildStatement(kb, concept.toKBHandle(), property,
                "Test statement");

        sut.upsertStatement(kb, statement);

        KBStatement mockStatement = buildStatement(kb, concept.toKBHandle(), property,
                "Test statement");
        assertThat(sut.exists(kb, mockStatement)).isTrue();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void thatExistsDoesNotFindNonExistingStatement(Reification reification) throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        KBConcept concept = buildConcept();
        KBProperty property = buildProperty();
        sut.createConcept(kb, concept);
        sut.createProperty(kb, property);
        KBStatement statement = buildStatement(kb, concept.toKBHandle(), property, "Test");

        sut.upsertStatement(kb, statement);

        KBStatement mockStatement = buildStatement(kb, concept.toKBHandle(), property,
                "Test statement");
        assertThat(sut.exists(kb, mockStatement)).isFalse();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void thatTheInstanceIsRetrievedInTheCorrectLanguage(Reification reification)
        throws Exception
    {
        setUp(reification);

        var germanInstance = buildInstanceWithLanguage("de");
        var englishInstance = buildInstanceWithLanguage("en");

        kb.setDefaultLanguage("en");
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createInstance(kb, germanInstance);

        // Create English instance and ensure that both have the same identifier
        sut.update(kb, (conn) -> {
            englishInstance.setIdentifier(germanInstance.getIdentifier());
            englishInstance.write(conn, kb);
        });

        var firstInstance = sut.readInstance(kb, germanInstance.getIdentifier()).get();
        assertThat(firstInstance.getLanguage()) //
                .as("Check that the English instance is retrieved.") //
                .isEqualTo("en");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void thatTheLanguageOfKbInstanceCanBeModified(Reification reification) throws Exception
    {
        setUp(reification);

        KBInstance englishInstance = buildInstanceWithLanguage("en");

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createInstance(kb, englishInstance);

        englishInstance.setLanguage("de");
        sut.updateInstance(kb, englishInstance);

        // Make sure we retrieve the German version now
        kb.setDefaultLanguage("de");

        KBInstance germanInstance = sut.readInstance(kb, englishInstance.getIdentifier()).get();
        assertThat(germanInstance.getLanguage())
                .as("Check that the language has successfully been changed.").isEqualTo("de");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void thatTheLanguageOfKbPropertyCanBeModified(Reification reification) throws Exception
    {
        setUp(reification);

        KBProperty englishProperty = buildPropertyWithLanguage("en");

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createProperty(kb, englishProperty);

        englishProperty.setLanguage("de");
        sut.updateProperty(kb, englishProperty);

        // Make sure we retrieve the German version now
        kb.setDefaultLanguage("de");

        KBProperty germanProperty = sut.readProperty(kb, englishProperty.getIdentifier()).get();
        assertThat(germanProperty.getLanguage())
                .as("Check that the language has successfully been changed.").isEqualTo("de");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void thatTheLanguageOfKbConceptCanBeModified(Reification reification) throws Exception
    {
        setUp(reification);

        KBConcept englishConcept = buildConceptWithLanguage("en");

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        sut.createConcept(kb, englishConcept);

        englishConcept.setLanguage("de");
        sut.updateConcept(kb, englishConcept);

        // Make sure we retrieve the German version now
        kb.setDefaultLanguage("de");

        KBConcept germanConcept = sut.readConcept(kb, englishConcept.getIdentifier(), true).get();
        assertThat(germanConcept.getLanguage())
                .as("Check that the language has successfully been changed.").isEqualTo("de");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readKnowledgeBaseProfiles_ShouldReturnValidHashMapWithProfiles(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        Map<String, KnowledgeBaseProfile> profiles = KnowledgeBaseProfile
                .readKnowledgeBaseProfiles();

        assertThat(profiles).allSatisfy((key, profile) -> assertThat(key).isNotNull());

    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readKBIdentifiers_ShouldReturnCorrectClassInstances(Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        KBConcept concept = buildConcept();
        sut.createConcept(kb, concept);
        KBInstance instance = buildInstance();
        sut.createInstance(kb, instance);
        KBProperty property = buildProperty();
        sut.createProperty(kb, property);

        assertThat(sut.readItem(kb, concept.getIdentifier()).get())
                .as("Check that reading a concept id returns an instance of KBConcept")
                .isInstanceOf(KBConcept.class);
        assertThat(sut.readItem(kb, instance.getIdentifier()).get())
                .as("Check that reading an instance id returns an instance of KBInstance")
                .isInstanceOf(KBInstance.class);
        assertThat(sut.readItem(kb, property.getIdentifier()).get())
                .as("Check that reading a property id returns an instance of KBProperty")
                .isInstanceOf(KBProperty.class);
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void checkIfKBIsEnabledById_WithExistingAndEnabledKB_ShouldReturnTrue(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        String repoId = kb.getRepositoryId();
        assertThat(sut.isKnowledgeBaseEnabled(project, repoId))
                .as("Check that correct accessibility value is returned for enabled kb ").isTrue();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void checkIfKBIsEnabledById_WithDisabledKBAndNonExistingId_ShouldReturnFalse(
            Reification reification)
        throws Exception
    {
        setUp(reification);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        kb.setEnabled(false);
        String repoId = kb.getRepositoryId();

        assertThat(sut.isKnowledgeBaseEnabled(project, repoId))
                .as("Check that correct accessibility value is returned for disabled kb ")
                .isFalse();

        assertThat(sut.isKnowledgeBaseEnabled(project, "NonExistingID"))
                .as("Check that correct accessibility value is returned for non existing id ")
                .isFalse();
    }

    @Test
    public void importKnowledgeBase_RdfXmlWithInternalDoctypeEntities() throws Exception
    {
        setUp(Reification.NONE);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        importKnowledgeBase("data/tagset_custom_label.rdf");
        setSchema(kb, OWL.CLASS, RDFS.SUBCLASSOF, RDF.TYPE, RDFS.COMMENT, RDFS.LABEL, RDF.PROPERTY);

        // The IRIs are written using the entities, so they must have been expanded
        assertThat(sut.listInstances(kb, TAGSET_NS + "CommonNoun", false)) //
                .extracting(KBHandle::getIdentifier, KBHandle::getName) //
                .containsExactlyInAnyOrder( //
                        tuple(TAGSET_NS + "NN", "Singular common noun"), //
                        tuple(TAGSET_NS + "NNS", "Plural common noun"));
    }

    @Test
    public void importKnowledgeBase_RdfXmlWithExternalEntity_DoesNotExpandIt() throws Exception
    {
        setUp(Reification.NONE);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var canary = "TOP-SECRET-XXE-CANARY";
        var secretFile = new File(temporaryFolder, "secret.txt");
        Files.writeString(secretFile.toPath(), canary, UTF_8);

        var rdf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE rdf:RDF [ <!ENTITY xxe SYSTEM "%s"> ]>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <rdf:Description rdf:about="http://example.org/test/xxe#thing">
                    <rdfs:label>&xxe;</rdfs:label>
                  </rdf:Description>
                </rdf:RDF>
                """.formatted(secretFile.toURI());

        // Rejecting the document is fine. What must never happen is that the file contents
        // make it into the knowledge base or into an error message.
        var thrown = catchThrowable(() -> {
            try (var is = new ByteArrayInputStream(rdf.getBytes(UTF_8))) {
                sut.importData(kb, "xxe.rdf", is);
            }
        });

        var statements = sut.read(kb, conn -> {
            try (var stmts = conn.getStatements(null, null, null)) {
                return stmts.stream().toList();
            }
        });
        assertThat(statements) //
                .extracting(stmt -> stmt.getObject().stringValue()) //
                .noneMatch(value -> value.contains(canary));

        if (thrown != null) {
            assertThat(ExceptionUtils.getStackTrace(thrown)).doesNotContain(canary);
        }
        else {
            assertThat(statements) //
                    .as("Check that the rest of the document has been imported") //
                    .extracting(stmt -> stmt.getSubject().stringValue()) //
                    .contains("http://example.org/test/xxe#thing");
        }
    }

    @Test
    public void importKnowledgeBase_OBO() throws Exception
    {
        setUp(Reification.NONE);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        importKnowledgeBase("data/example.obo");

        assertThatOboExampleHasBeenImported();
    }

    @Test
    public void importKnowledgeBase_OBO_gzip() throws Exception
    {
        setUp(Reification.NONE);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        importKnowledgeBase("data/example.obo.gz");

        assertThatOboExampleHasBeenImported();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readHandles_WithExistingConcepts_ShouldReturnHandlePerId(Reification reification)
        throws Exception
    {
        setUp(reification);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var c1 = buildConcept();
        c1.setName("Alpha");
        sut.createConcept(kb, c1);
        var c2 = buildConcept();
        c2.setName("Beta");
        sut.createConcept(kb, c2);

        var result = sut.readHandles(kb, asList(c1.getIdentifier(), c2.getIdentifier()));

        assertThat(result).hasSize(2);
        assertThat(result.get(c1.getIdentifier())).extracting(KBHandle::getName).isEqualTo("Alpha");
        assertThat(result.get(c2.getIdentifier())).extracting(KBHandle::getName).isEqualTo("Beta");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readHandles_WithMissingIds_ShouldReturnStubHandles(Reification reification)
        throws Exception
    {
        setUp(reification);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var c1 = buildConcept();
        c1.setName("Alpha");
        sut.createConcept(kb, c1);

        var missingId = "https://nonexistent.identifier.test/x";
        var result = sut.readHandles(kb, asList(c1.getIdentifier(), missingId));

        assertThat(result).hasSize(2);
        assertThat(result.get(c1.getIdentifier())).extracting(KBHandle::getName).isEqualTo("Alpha");
        assertThat(result.get(missingId)) //
                .as("Missing IDs get stub handles with the requested identifier and no name")
                .isNotNull() //
                .extracting(KBHandle::getIdentifier, KBHandle::getName) //
                .containsExactly(missingId, null);
    }

    @Test
    public void readHandles_WithEmptyInput_ShouldReturnEmptyMap() throws Exception
    {
        setUp(Reification.NONE);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        assertThat(sut.readHandles(kb, List.of())).isEmpty();
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readHandles_WithDuplicateIds_ShouldDeduplicate(Reification reification)
        throws Exception
    {
        setUp(reification);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var c1 = buildConcept();
        c1.setName("Alpha");
        sut.createConcept(kb, c1);

        var result = sut.readHandles(kb,
                asList(c1.getIdentifier(), c1.getIdentifier(), c1.getIdentifier()));

        assertThat(result).hasSize(1);
        assertThat(result.get(c1.getIdentifier())).extracting(KBHandle::getName).isEqualTo("Alpha");
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readHandles_WithMoreIdsThanBatchSize_ShouldChunkAndMergeResults(
            Reification reification)
        throws Exception
    {
        var props = new KnowledgeBasePropertiesImpl();
        props.setReadBatchSize(2);
        setUp(reification, props);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var concepts = new ArrayList<KBConcept>();
        for (var i = 0; i < 5; i++) {
            var c = buildConcept();
            c.setName("Concept" + i);
            sut.createConcept(kb, c);
            concepts.add(c);
        }

        var ids = concepts.stream().map(KBConcept::getIdentifier).toList();
        var result = sut.readHandles(kb, ids);

        // 5 ids with batch size 2 => 3 chunks (2 + 2 + 1). All results must still be present.
        assertThat(result).hasSize(5);
        for (var i = 0; i < 5; i++) {
            assertThat(result.get(concepts.get(i).getIdentifier())) //
                    .extracting(KBHandle::getName) //
                    .isEqualTo("Concept" + i);
        }
    }

    @ParameterizedTest(name = "Reification = {0}")
    @MethodSource("data")
    public void readHandles_Project_ShouldResolveAcrossEnabledKBs(Reification reification)
        throws Exception
    {
        setUp(reification);
        sut.registerKnowledgeBase(kb, sut.getNativeConfig());
        var kb2 = buildKnowledgeBase(project, "Second KB", reification);
        sut.registerKnowledgeBase(kb2, sut.getNativeConfig());

        var c1 = buildConcept();
        c1.setName("InFirst");
        sut.createConcept(kb, c1);

        var c2 = buildConcept();
        c2.setName("InSecond");
        sut.createConcept(kb2, c2);

        var result = sut.readHandles(project, asList(c1.getIdentifier(), c2.getIdentifier()));

        assertThat(result).hasSize(2);
        assertThat(result.get(c1.getIdentifier())).extracting(KBHandle::getName)
                .isEqualTo("InFirst");
        assertThat(result.get(c2.getIdentifier())).extracting(KBHandle::getName)
                .isEqualTo("InSecond");
    }

    @Test
    public void listDatasets_ShouldReturnNamedGraphsExcludingTheDefaultGraph() throws Exception
    {
        setUp(Reification.NONE);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        var schemaGraph = "http://example.org/graph/schema";
        var instanceGraph = "http://example.org/graph/instances";

        sut.update(kb, conn -> {
            var vf = conn.getValueFactory();
            // Statements in two distinct named graphs
            conn.add(vf.createIRI("http://example.org/s1"), RDFS.LABEL, vf.createLiteral("S1"),
                    vf.createIRI(schemaGraph));
            conn.add(vf.createIRI("http://example.org/s2"), RDFS.LABEL, vf.createLiteral("S2"),
                    vf.createIRI(instanceGraph));
            // A statement in the default graph (no context) which must not be reported as a graph
            conn.add(vf.createIRI("http://example.org/s3"), RDFS.LABEL, vf.createLiteral("S3"));
        });

        var datasets = sut.listDatasets(kb);

        assertThat(datasets) //
                .as("Named graphs are returned sorted and the default graph is excluded") //
                .containsExactly(instanceGraph, schemaGraph);
    }

    @Test
    public void listDatasets_WithoutNamedGraphs_ShouldReturnEmptyList() throws Exception
    {
        setUp(Reification.NONE);

        sut.registerKnowledgeBase(kb, sut.getNativeConfig());

        sut.update(kb, conn -> {
            var vf = conn.getValueFactory();
            // Only a statement in the default graph
            conn.add(vf.createIRI("http://example.org/s1"), RDFS.LABEL, vf.createLiteral("S1"));
        });

        assertThat(sut.listDatasets(kb)) //
                .as("No named graphs present") //
                .isEmpty();
    }

    // Helper
    private Project createProject(String name)
    {
        return testFixtures.createProject(name);
    }

    private KnowledgeBase buildKnowledgeBase(Project aProject, String aName,
            Reification aReification)
    {
        return testFixtures.buildKnowledgeBase(aProject, aName, aReification);
    }

    private KBConcept buildConcept()
    {
        return testFixtures.buildConcept();
    }

    private KBConcept buildConceptWithLanguage(String aLanguage)
    {
        return testFixtures.buildConceptWithLanguage(aLanguage);
    }

    private KBProperty buildProperty()
    {
        return testFixtures.buildProperty();
    }

    private KBProperty buildPropertyWithLanguage(String aLanguage)
    {
        return testFixtures.buildPropertyWithLanguage(aLanguage);
    }

    private KBInstance buildInstance()
    {
        return testFixtures.buildInstance();
    }

    private KBInstance buildInstanceWithLanguage(String aLanguage)
    {
        return testFixtures.buildInstanceWithLanguage(aLanguage);
    }

    private KBStatement buildStatement(KnowledgeBase knowledgeBase, KBHandle conceptHandle,
            KBProperty aProperty, String value)
    {
        KBStatement stmt = testFixtures.buildStatement(conceptHandle, aProperty, value);
        return stmt;
    }

    private boolean isNotAbstractNorClosedStatement(KBStatement statement)
    {
        String id = statement.getProperty().getIdentifier();
        return !(id.endsWith("#abstract") || id.endsWith("#closed"));
    }

    private boolean hasImplicitNamespace(KBObject handle)
    {
        return IriConstants.IMPLICIT_NAMESPACES.stream()
                .anyMatch(ns -> handle.getIdentifier().startsWith(ns));
    }

    private void assertThatOboExampleHasBeenImported()
    {
        setSchema(kb, OWL.CLASS, RDFS.SUBCLASSOF, RDF.TYPE, RDFS.COMMENT, RDFS.LABEL, RDF.PROPERTY);

        var concepts = sut.listAllConcepts(kb, false);
        assertThat(concepts) //
                .as("Check that all terms have been imported, obsolete ones as deprecated")
                .extracting(KBHandle::getIdentifier, KBHandle::getName, KBHandle::isDeprecated) //
                .containsExactlyInAnyOrder( //
                        tuple(OBO_NS + "INCTEST_0000001", "plant part", false), //
                        tuple(OBO_NS + "INCTEST_0000002", "root", false), //
                        tuple(OBO_NS + "INCTEST_0000003", "leaf", false), //
                        tuple(OBO_NS + "INCTEST_0000004", "shoot system", false), //
                        tuple(OBO_NS + "INCTEST_0000006", "stem", false), //
                        tuple(OBO_NS + "INCTEST_0000005", "obsolete plant organ", true),
                        // The alt_id of "root" becomes a deprecated class without a label
                        tuple(OBO_NS + "INCTEST_0000020", null, true));

        assertThat(sut.listRootConcepts(kb, false)) //
                .as("Check that the is_a hierarchy has been imported") //
                .extracting(KBHandle::getIdentifier) //
                .containsExactlyInAnyOrder(OBO_NS + "INCTEST_0000001", OBO_NS + "INCTEST_0000005",
                        OBO_NS + "INCTEST_0000020");
        assertThat(sut.listChildConcepts(kb, OBO_NS + "INCTEST_0000001", false)) //
                .extracting(KBHandle::getName) //
                .containsExactlyInAnyOrder("root", "leaf", "shoot system", "stem");

        var root = concepts.stream().filter(c -> "root".equals(c.getName())).findFirst().get();
        assertThat(sut.listStatements(kb, root, true)) //
                .as("Check that synonyms, including non-ASCII ones, have been imported") //
                .extracting(stmt -> String.valueOf(stmt.getValue())) //
                .contains("Wurzel", "根", "taproot");

        var shootSystem = concepts.stream().filter(c -> "shoot system".equals(c.getName()))
                .findFirst().get();
        assertThat(sut.listStatements(kb, shootSystem, true)) //
                .extracting(stmt -> String.valueOf(stmt.getValue())) //
                .contains("Sprossachse (grün)");

        // The label search uses the full text index, so this checks that it has been built
        var hits = sut.read(kb, conn -> SPARQLQueryBuilder.forItems(kb) //
                .withPrefLabelProperties(SPARQLQueryBuilder.forItems(kb) //
                        .resolvePrefLabelProperties(conn)) //
                .withLabelStartingWith("shoot") //
                .retrieveLabel() //
                .asHandles(conn, true));
        assertThat(hits).extracting(KBHandle::getName).containsExactly("shoot system");
    }

    private void importKnowledgeBase(String resourceName) throws Exception
    {
        ClassLoader classLoader = getClass().getClassLoader();
        String fileName = classLoader.getResource(resourceName).getFile();
        try (InputStream is = classLoader.getResourceAsStream(resourceName)) {
            sut.importData(kb, fileName, is);
        }
    }

    private void setReadOnly(KnowledgeBase kb)
    {
        kb.setReadOnly(true);
        sut.updateKnowledgeBase(kb, sut.getKnowledgeBaseConfig(kb));
    }

    private void setSchema(KnowledgeBase kb, IRI classIri, IRI subclassIri, IRI typeIri,
            IRI descriptionIri, IRI labelIri, IRI propertyTypeIri)
    {
        kb.setClassIri(classIri.stringValue());
        kb.setSubclassIri(subclassIri.stringValue());
        kb.setTypeIri(typeIri.stringValue());
        kb.setDescriptionIri(descriptionIri.stringValue());
        kb.setLabelIri(labelIri.stringValue());
        kb.setPropertyTypeIri(propertyTypeIri.stringValue());
        sut.updateKnowledgeBase(kb, sut.getKnowledgeBaseConfig(kb));
    }

    @SpringBootConfiguration
    public static class SpringConfig
    {
        // No content
    }
}
