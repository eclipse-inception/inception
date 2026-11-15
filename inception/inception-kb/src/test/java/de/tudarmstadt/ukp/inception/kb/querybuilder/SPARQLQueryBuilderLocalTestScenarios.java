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

import static de.tudarmstadt.ukp.inception.kb.RdfImportParserConfigs.doctypeTolerantParserConfig;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.newPerThreadSslCheckingHttpClientBuilder;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.restoreSslVerification;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.suspendSslVerification;
import static de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryBuilderAsserts.asHandle;
import static de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryBuilderAsserts.asHandles;
import static de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryBuilderAsserts.exists;
import static de.tudarmstadt.ukp.inception.kb.util.TestFixtures.isReachable;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.contentOf;
import static org.eclipse.rdf4j.rio.RDFFormat.RDFXML;
import static org.eclipse.rdf4j.rio.RDFFormat.TURTLE;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.function.FailableBiConsumer;
import org.apache.commons.lang3.tuple.Pair;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.SKOS;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.inception.kb.RepositoryType;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;

public class SPARQLQueryBuilderLocalTestScenarios
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    static final String TURTLE_PREFIX = String.join("\n", //
            "@base <http://example.org/> .", //
            "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .", //
            "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .", //
            "@prefix so: <http://schema.org/> .", //
            "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .", //
            "@prefix owl: <http://www.w3.org/2002/07/owl#> .");

    static final String DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE = contentOf(
            new File("src/test/resources/turtle/data_labels_and_descriptions_with_language.ttl"),
            UTF_8);

    static final String DATA_LABELS_WITHOUT_LANGUAGE = String.join("\n", //
            "<#green-goblin>", //
            "    rdfs:label 'Green Goblin' .", //
            "", //
            "<#lucky-green>", //
            "    rdfs:label 'Lucky Green' .", //
            "", //
            "<#red-goblin>", //
            "    rdfs:label 'Red Goblin' .");

    static final String DATA_MULTIPLE_LABELS = String.join("\n", //
            "<#example>", //
            "    rdfs:label 'specimen' ;", //
            "    rdfs:label 'sample' ;", //
            "    rdfs:label 'instance' ;", //
            "    rdfs:label 'case'  .");

    static final String DATA_ADDITIONAL_SEARCH_PROPERTIES = contentOf(
            new File("src/test/resources/turtle/data_additional_search_properties.ttl"), UTF_8);

    static final String DATA_ADDITIONAL_SEARCH_PROPERTIES_2 = contentOf(
            new File("src/test/resources/turtle/data_additional_search_properties_2.ttl"), UTF_8);

    static final String LABEL_SUBPROPERTY = String.join("\n", //
            "<#sublabel>", //
            "    rdfs:subPropertyOf rdfs:label .", //
            "", //
            "<#green-goblin>", //
            "    <#sublabel> 'Green Goblin' .");

    /**
     * This dataset contains a hierarchy of classes and instances with a naming scheme. There is an
     * implicit and an explicit root class. All classes have "class" in their name. Subclasses start
     * with "subclass" and then a number. Instances start with the number of the class to which they
     * belong followed by a number.
     */
    static final String DATA_CLASS_RDFS_HIERARCHY = String.join("\n", //
            "<#explicitRoot>", //
            "    rdf:type rdfs:Class .", //
            "<#subclass1>", //
            "    rdf:type rdfs:Class ;", //
            "    rdfs:subClassOf <#explicitRoot> .", //
            "<#subclass1-1>", //
            "    rdfs:subClassOf <#subclass1> .", //
            "<#subclass1-1-1>", //
            "    rdfs:subClassOf <#subclass1-1> .", //
            "<#subclass2>", //
            "    rdfs:subClassOf <#explicitRoot> .", //
            "<#subclass3>", //
            "    rdfs:subClassOf <#implicitRoot> .", //
            "<#0-instance-1>", //
            "    rdf:type <#root> .", //
            "<#1-instance-1>", //
            "    rdf:type <#subclass1> .", //
            "<#2-instance-2>", //
            "    rdf:type <#subclass2> .", //
            "<#3-instance-3>", //
            "    rdf:type <#subclass3> .", //
            "<#1-1-1-instance-4>", //
            "    rdf:type <#subclass1-1-1> .");

    /**
     * This dataset contains properties, some in a hierarchical relationship. There is again a
     * naming scheme: all properties have "property" in their name. Subproperties start with
     * "subproperty" and then a number. The dataset also contains some non-properties to be able to
     * ensure that queries limited to properties do not return non-properties.
     */
    static final String DATA_PROPERTIES = String.join("\n", //
            "<#explicitRoot>", //
            "    rdf:type rdfs:Class .", //
            "<#property-1>", //
            "    rdf:type rdf:Property ;", //
            "    skos:prefLabel 'Property 1' ;", //
            "    so:description 'Property One' ;", //
            "    rdfs:domain <#explicitRoot> ;", //
            "    rdfs:range xsd:string .", //
            "<#property-2>", //
            "    rdf:type rdf:Property ;", //
            "    skos:prefLabel 'Property 2' ;", //
            "    so:description 'Property Two' ;", //
            "    rdfs:domain <#subclass1> ;", //
            "    rdfs:range xsd:Integer .", //
            "<#property-3>", //
            "    rdf:type rdf:Property ;", //
            "    skos:prefLabel 'Property 3' ;", //
            "    so:description 'Property Three' .", //
            "<#subproperty-1-1>", //
            "    rdfs:subPropertyOf <#property-1> ;", //
            "    skos:prefLabel 'Subproperty 1-1' ;", //
            "    so:description 'Property One-One' .", //
            "<#subproperty-1-1-1>", //
            "    rdfs:subPropertyOf <#subproperty-1-1> ;", //
            "    skos:prefLabel 'Subproperty 1-1-1' ;", //
            "    so:description 'Property One-One-One' .", //
            "<#subclass1>", //
            "    rdf:type rdfs:Class ;", //
            "    rdfs:subClassOf <#explicitRoot> ;", //
            "    <#implicit-property-1> 'value1' .");

    static final String DATA_DEPRECATED = """
            <#class-1>
                rdf:type rdfs:Class ;
                rdfs:label 'Class 1' ;
                owl:deprecated true .
            <#class-2>
                rdf:type rdfs:Class ;
                rdfs:label 'Class 2' ;
                owl:deprecated false .
            <#class-3>
                rdf:type rdfs:Class ;
                rdfs:label 'Class 3' ;
                owl:deprecated 'lala' .
            <#class-4>
                rdf:type rdfs:Class ;
                rdfs:label 'Class 4' ;
                owl:deprecated 0 .
            <#class-5>
                rdf:type rdfs:Class ;
                rdfs:label 'Class 5' ;
                owl:deprecated 1 .
            <#instance-1>
                rdf:type <#class-1> ;
                rdfs:label 'Instance 1' ;
                owl:deprecated true .
            <#property-1>
                rdf:type rdf:Property ;
                rdfs:label 'Property 1' ;
                owl:deprecated true .
            """;

    private KnowledgeBase kb;

    @BeforeEach
    public void setUp()
    {
        suspendSslVerification();

        kb = new KnowledgeBase();
        kb.setDefaultLanguage("en");
        kb.setType(RepositoryType.LOCAL);
        kb.setFullTextSearchIri(null);
        kb.setMaxResults(100);

        initRdfsMapping(kb);
    }

    @BeforeEach
    public void testWatcher(TestInfo aTestInfo)
    {
        String methodName = aTestInfo.getTestMethod().map(Method::getName).orElse("<unknown>");
        LOG.info("=== {} === {} =====================", methodName, aTestInfo.getDisplayName());

        suspendSslVerification();
    }

    @AfterEach
    public void tearDown()
    {
        restoreSslVerification();
    }

    static List<Scenario> tests() throws Exception
    {
        return asList( //
                new Scenario("testWithLabelStartingWith_withLanguage_FTS_1",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelStartingWith_withLanguage_FTS_1),
                new Scenario("testWithLabelStartingWith_withLanguage_FTS_2",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelStartingWith_withLanguage_FTS_2),
                new Scenario("testWithLabelStartingWith_withLanguage_FTS_3",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelStartingWith_withLanguage_FTS_3),
                new Scenario("testWithLabelStartingWith_withLanguage_FTS_4",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelStartingWith_withLanguage_FTS_4),
                new Scenario("testWithLabelStartingWith_withLanguage_FTS_5",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelStartingWith_withLanguage_FTS_5),
                new Scenario("testWithLabelStartingWith_withLanguage_noFTS",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelStartingWith_withLanguage_noFTS),
                new Scenario("testWithLabelContainingAnyOf_pets_ttl_noFTS",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelContainingAnyOf_pets_ttl_noFTS),
                new Scenario("thatRootsCanBeRetrieved_owl",
                        SPARQLQueryBuilderLocalTestScenarios::thatRootsCanBeRetrieved_owl),
                new Scenario("thatIntersectionClassIsChildOfItsOperands_owl",
                        SPARQLQueryBuilderLocalTestScenarios::thatIntersectionClassIsChildOfItsOperands_owl),
                new Scenario("testWithLabelContainingAnyOf_withLanguage_noFTS",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelContainingAnyOf_withLanguage_noFTS),
                new Scenario("testWithLabelMatchingAnyOf_withFallbackLanguages",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelMatchingAnyOf_withFallbackLanguages),
                new Scenario("testWithLabelContainingAnyOf_withLanguage",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelContainingAnyOf_withLanguage),
                new Scenario("testWithLabelMatchingAnyOf_withLanguage",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelMatchingAnyOf_withLanguage),
                new Scenario("testWithLabelMatchingAnyOf_withLanguage_noFTS",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelMatchingAnyOf_withLanguage_noFTS),
                new Scenario("testWithLabelStartingWith_withoutLanguage",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelStartingWith_withoutLanguage),
                new Scenario("testWithLabelStartingWith_withoutLanguage_noFTS",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelStartingWith_withoutLanguage_noFTS),
                new Scenario("testWithLabelMatchingExactlyAnyOf_subproperty",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelMatchingExactlyAnyOf_subproperty),
                new Scenario("testWithLabelMatchingExactlyAnyOf_subproperty_noFTS",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelMatchingExactlyAnyOf_subproperty_noFTS),
                new Scenario("testWithLabelMatchingExactlyAnyOf_withLanguage_noFTS",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelMatchingExactlyAnyOf_withLanguage_noFTS),
                new Scenario("testWithLabelMatchingExactlyAnyOf_withLanguage",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelMatchingExactlyAnyOf_withLanguage),
                new Scenario("thatExistsReturnsTrueWhenDataQueriedForExists",
                        SPARQLQueryBuilderLocalTestScenarios::thatExistsReturnsTrueWhenDataQueriedForExists),
                new Scenario("thatLabelsInAnyLanguageAreRetrievedWhenNoDefaultLanguage",
                        SPARQLQueryBuilderLocalTestScenarios::thatLabelsInAnyLanguageAreRetrievedWhenNoDefaultLanguage),
                new Scenario("thatLabelsAndDescriptionsWithLanguageArePreferred",
                        SPARQLQueryBuilderLocalTestScenarios::thatLabelsAndDescriptionsWithLanguageArePreferred),
                new Scenario("thatSearchOverMultipleLabelsWorks",
                        SPARQLQueryBuilderLocalTestScenarios::thatSearchOverMultipleLabelsWorks),
                new Scenario("thatResolveMatchingPropertiesWorks",
                        SPARQLQueryBuilderLocalTestScenarios::thatResolveMatchingPropertiesWorks),
                new Scenario("thatMatchingAgainstAdditionalSearchPropertiesWorks",
                        SPARQLQueryBuilderLocalTestScenarios::thatMatchingAgainstAdditionalSearchPropertiesWorks),
                new Scenario("thatMatchingAgainstAdditionalSearchPropertiesWorks2",
                        SPARQLQueryBuilderLocalTestScenarios::thatMatchingAgainstAdditionalSearchPropertiesWorks2),
                new Scenario("thatExistsReturnsFalseWhenDataQueriedForDoesNotExist",
                        SPARQLQueryBuilderLocalTestScenarios::thatExistsReturnsFalseWhenDataQueriedForDoesNotExist),
                new Scenario("thatExplicitClassCanBeRetrievedByItsIdentifier",
                        SPARQLQueryBuilderLocalTestScenarios::thatExplicitClassCanBeRetrievedByItsIdentifier),
                new Scenario("thatImplicitClassCanBeRetrievedByItsIdentifier",
                        SPARQLQueryBuilderLocalTestScenarios::thatImplicitClassCanBeRetrievedByItsIdentifier),
                new Scenario("thatNonClassCannotBeRetrievedByItsIdentifier",
                        SPARQLQueryBuilderLocalTestScenarios::thatNonClassCannotBeRetrievedByItsIdentifier),
                new Scenario("thatCanRetrieveItemInfoForIdentifier",
                        SPARQLQueryBuilderLocalTestScenarios::thatCanRetrieveItemInfoForIdentifier),
                new Scenario("thatAllPropertiesCanBeRetrieved",
                        SPARQLQueryBuilderLocalTestScenarios::thatAllPropertiesCanBeRetrieved),
                new Scenario("thatDeprecationStatusCanBeRetrieved",
                        SPARQLQueryBuilderLocalTestScenarios::thatDeprecationStatusCanBeRetrieved),
                new Scenario("thatPropertyQueryLimitedToDescendantsDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatPropertyQueryLimitedToDescendantsDoesNotReturnOutOfScopeResults),
                new Scenario("thatPropertyQueryLimitedToChildrenDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatPropertyQueryLimitedToChildrenDoesNotReturnOutOfScopeResults),
                new Scenario("thatPropertyQueryLimitedToDomainDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatPropertyQueryLimitedToDomainDoesNotReturnOutOfScopeResults),
                new Scenario("thatQueryLimitedToRootClassesDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatQueryLimitedToRootClassesDoesNotReturnOutOfScopeResults),
                new Scenario("thatQueryWithExplicitRootClassDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatQueryWithExplicitRootClassDoesNotReturnOutOfScopeResults),
                new Scenario("thatNonRootClassCanBeUsedAsExplicitRootClass",
                        SPARQLQueryBuilderLocalTestScenarios::thatNonRootClassCanBeUsedAsExplicitRootClass),
                new Scenario("thatQueryLimitedToClassesDoesNotReturnInstances",
                        SPARQLQueryBuilderLocalTestScenarios::thatQueryLimitedToClassesDoesNotReturnInstances),
                new Scenario("thatQueryLimitedToInstancesDoesNotReturnClasses",
                        SPARQLQueryBuilderLocalTestScenarios::thatQueryLimitedToInstancesDoesNotReturnClasses),
                new Scenario("thatClassQueryLimitedToAnchestorsDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatClassQueryLimitedToAnchestorsDoesNotReturnOutOfScopeResults),
                new Scenario("thatClassQueryLimitedToParentsDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatClassQueryLimitedToParentsDoesNotReturnOutOfScopeResults),
                new Scenario("thatClassQueryLimitedToChildrenDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatClassQueryLimitedToChildrenDoesNotReturnOutOfScopeResults),
                new Scenario("thatClassQueryLimitedToDescendantsDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatClassQueryLimitedToDescendantsDoesNotReturnOutOfScopeResults),
                new Scenario("thatInstanceQueryLimitedToParentsDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatInstanceQueryLimitedToParentsDoesNotReturnOutOfScopeResults),
                new Scenario("thatInstanceQueryLimitedToAnchestorsDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatInstanceQueryLimitedToAnchestorsDoesNotReturnOutOfScopeResults),
                new Scenario("thatInstanceQueryLimitedToChildrenDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatInstanceQueryLimitedToChildrenDoesNotReturnOutOfScopeResults),
                new Scenario("thatInstanceQueryLimitedToDescendantsDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatInstanceQueryLimitedToDescendantsDoesNotReturnOutOfScopeResults),
                new Scenario("thatItemQueryLimitedToChildrenDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatItemQueryLimitedToChildrenDoesNotReturnOutOfScopeResults),
                new Scenario("thatItemQueryLimitedToDescendantsDoesNotReturnOutOfScopeResults",
                        SPARQLQueryBuilderLocalTestScenarios::thatItemQueryLimitedToDescendantsDoesNotReturnOutOfScopeResults),
                new Scenario("testWithLabelStartingWith_customLabelProperty",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelStartingWith_customLabelProperty),
                new Scenario("testWithLabelStartingWith_customLabelProperty_rdfXml",
                        SPARQLQueryBuilderLocalTestScenarios::testWithLabelStartingWith_customLabelProperty_rdfXml));
    }

    static class Scenario
    {
        final String name;
        final FailableBiConsumer<Repository, KnowledgeBase, Exception> implementation;

        public Scenario(String aName,
                FailableBiConsumer<Repository, KnowledgeBase, Exception> aImplementation)
        {
            name = aName;
            implementation = aImplementation;
        }
    }

    static Repository buildSparqlRepository(String aUrl)
    {
        var repo = new SPARQLRepository(aUrl);
        repo.setHttpClient(newPerThreadSslCheckingHttpClientBuilder().build());
        repo.setAdditionalHttpHeaders(Map.of("User-Agent", "INCEpTION/0.0.1-SNAPSHOT"));
        repo.init();
        return repo;
    }

    static Repository buildSparqlRepository(String aQueryUrl, String aUpdateUrl)
    {
        var repo = new SPARQLRepository(aQueryUrl, aUpdateUrl);
        repo.setHttpClient(newPerThreadSslCheckingHttpClientBuilder().build());
        repo.setAdditionalHttpHeaders(Map.of("User-Agent", "INCEpTION/0.0.1-SNAPSHOT"));
        repo.init();
        return repo;
    }

    /**
     * Checks that {@code SPARQLQueryBuilder#exists(RepositoryConnection, boolean)} can return
     * {@code true} by querying for a list of all classes in {@link #DATA_CLASS_RDFS_HIERARCHY}
     * which contains a number of classes.
     * 
     * @throws Exception
     *             -
     */
    static void thatExistsReturnsTrueWhenDataQueriedForExists(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var result = exists(aRepository, SPARQLQueryBuilder.forClasses(aKB));

        assertThat(result).isTrue();
    }

    /**
     * If the KB has no default language set, then labels and descriptions in <b>any</b> language
     * are returned (including untagged ones). When a value is available both with and without a
     * language tag, the untagged one is preferred.
     *
     * @throws Exception
     *             -
     */
    static void thatLabelsInAnyLanguageAreRetrievedWhenNoDefaultLanguage(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        aKB.setDefaultLanguage(null);

        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE);

        // green-goblin has both untagged and language-tagged labels/descriptions - the untagged
        // ones
        // are preferred.
        var greenGoblin = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withIdentifier("http://example.org/#green-goblin") //
                .retrieveLabel() //
                .retrieveDescription());

        assertThat(greenGoblin)
                .usingRecursiveFieldByFieldElementComparatorOnFields("identifier", "name",
                        "description", "language")
                .containsExactlyInAnyOrder(KBHandle.builder()
                        .withIdentifier("http://example.org/#green-goblin").withName("Green Goblin")
                        .withDescription("Little green monster").build());

        // automobile has only language-tagged labels - previously none would be returned (the
        // untagged-only filter excluded them), now one of them is.
        var automobile = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withIdentifier("http://example.org/#automobile") //
                .retrieveLabel());

        assertThat(automobile).hasSize(1);
        assertThat(automobile.get(0).getName()).isIn("Automobil", "automobile", "automóvil",
                "automóvel", "auto");
    }

    /**
     * If the KB has a default language set, then labels/descriptions in that language should be
     * preferred it is permitted to fall back to labels/descriptions without any language. The
     * dataset contains only labels for French but no descriptions, so it should fall back to
     * returning the description without any language.
     * 
     * @throws Exception
     *             -
     */
    static void thatLabelsAndDescriptionsWithLanguageArePreferred(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        // The dataset contains only labels for French but no descriptions
        aKB.setDefaultLanguage("fr");

        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withIdentifier("http://example.org/#green-goblin") //
                .retrieveLabel() //
                .retrieveDescription());

        assertThat(results).isNotEmpty();
        assertThat(results)
                .usingRecursiveFieldByFieldElementComparatorOnFields("identifier", "name",
                        "description", "language")
                .containsExactlyInAnyOrder(new KBHandle("http://example.org/#green-goblin",
                        "Goblin vert", "Little green monster", "fr"));
    }

    static void thatSearchOverMultipleLabelsWorks(Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_MULTIPLE_LABELS);

        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        for (var term : asList("specimen", "sample", "instance", "case")) {
            var results = asHandles(aRepository, SPARQLQueryBuilder //
                    .forItems(aKB) //
                    .withPrefLabelProperties(prefLabels) //
                    .withLabelMatchingAnyOf(term) //
                    .retrieveLabel());

            assertThat(results)
                    .usingRecursiveFieldByFieldElementComparatorOnFields("identifier", "name")
                    .containsExactlyInAnyOrder(new KBHandle("http://example.org/#example", term));
        }
    }

    static void thatResolveMatchingPropertiesWorks(Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        aKB.setLabelIri("http://www.w3.org/2000/01/rdf-schema#prefLabel");
        aKB.setAdditionalMatchingProperties(asList("http://www.w3.org/2000/01/rdf-schema#label"));

        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_ADDITIONAL_SEARCH_PROPERTIES);

        try (var conn = aRepository.getConnection()) {
            var forItems = (SPARQLQueryBuilder) SPARQLQueryBuilder.forItems(aKB);
            assertThat(forItems.resolvePrefLabelProperties(conn))
                    .containsExactlyInAnyOrder("http://www.w3.org/2000/01/rdf-schema#prefLabel");
            assertThat(forItems.resolveAdditionalMatchingProperties(conn))
                    .containsExactlyInAnyOrder("http://www.w3.org/2000/01/rdf-schema#label");
        }
    }

    static void thatMatchingAgainstAdditionalSearchPropertiesWorks(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        aKB.setLabelIri("http://www.w3.org/2000/01/rdf-schema#prefLabel");
        aKB.setAdditionalMatchingProperties(asList("http://www.w3.org/2000/01/rdf-schema#label"));

        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_ADDITIONAL_SEARCH_PROPERTIES);

        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        for (var term : asList("specimen", "sample", "instance", "case")) {
            var results = asHandles(aRepository, SPARQLQueryBuilder //
                    .forItems(aKB) //
                    .withPrefLabelProperties(prefLabels) //
                    .withAdditionalMatchingProperties(aKB.getAdditionalMatchingProperties()) //
                    .withLabelMatchingAnyOf(term) //
                    .retrieveLabel());

            assertThat(results)
                    .usingRecursiveFieldByFieldElementComparatorOnFields("identifier", "name")
                    .containsExactlyInAnyOrder(
                            new KBHandle("http://example.org/#example", "specimen"));
        }
    }

    static void thatMatchingAgainstAdditionalSearchPropertiesWorks2(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        aKB.setLabelIri("http://www.w3.org/2000/01/rdf-schema#prefLabel");
        aKB.setAdditionalMatchingProperties(asList("http://www.w3.org/2000/01/rdf-schema#label"));

        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_ADDITIONAL_SEARCH_PROPERTIES_2);

        var queriesWithMatchTerms = asList(//
                Pair.of("hand", //
                        asList("Hand structure (body structure)", "Hand structure", "Hand")),
                Pair.of("hand structure", //
                        asList("Hand structure (body structure)", "Hand structure", "Hand")),
                Pair.of("body structure", //
                        asList("Hand structure (body structure)", "Hand structure")));

        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        for (var queryPair : queriesWithMatchTerms) {
            var results = asHandles(aRepository, SPARQLQueryBuilder //
                    .forItems(aKB) //
                    .withPrefLabelProperties(prefLabels) //
                    .withAdditionalMatchingProperties(aKB.getAdditionalMatchingProperties()) //
                    .withLabelMatchingAnyOf(queryPair.getKey()) //
                    .retrieveLabel());

            var expectedKBHandle = new KBHandle("http://example.org/#example",
                    "Hand structure (body structure)");
            queryPair.getValue().forEach(v -> expectedKBHandle.addMatchTerm(v, null));

            assertThat(results) //
                    .usingRecursiveFieldByFieldElementComparator(
                            RecursiveComparisonConfiguration.builder() //
                                    .withComparedFields("identifier", "name", "matchTerms") //
                                    .withIgnoredCollectionOrderInFields("matchTerms") //
                                    .build())
                    .containsExactlyInAnyOrder(expectedKBHandle);
        }
    }

    /**
     * Checks that {@code SPARQLQueryBuilder#exists(RepositoryConnection, boolean)} can return
     * {@code false} by querying for the parent of a root class in
     * {@link #DATA_CLASS_RDFS_HIERARCHY} which does not exist.
     * 
     * @throws Exception
     *             -
     */
    static void thatExistsReturnsFalseWhenDataQueriedForDoesNotExist(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var result = exists(aRepository,
                SPARQLQueryBuilder.forClasses(aKB).parentsOf("http://example.org/#explicitRoot"));

        assertThat(result).isFalse();
    }

    /**
     * Checks that an explicitly defined class can be retrieved using its identifier.
     * 
     * @throws Exception
     *             -
     */
    static void thatExplicitClassCanBeRetrievedByItsIdentifier(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var result = exists(aRepository, SPARQLQueryBuilder.forClasses(aKB)
                .withIdentifier("http://example.org/#explicitRoot"));

        assertThat(result).isTrue();
    }

    /**
     * Checks that an implicitly defined class can be retrieved using its identifier.
     * 
     * @throws Exception
     *             -
     */
    static void thatImplicitClassCanBeRetrievedByItsIdentifier(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var result = exists(aRepository, SPARQLQueryBuilder.forClasses(aKB)
                .withIdentifier("http://example.org/#implicitRoot"));

        assertThat(result).isTrue();
    }

    /**
     * Checks that a either explicitly nor implicitly defined class can be retrieved using its
     * identifier.
     * 
     * @throws Exception
     *             -
     */
    static void thatNonClassCannotBeRetrievedByItsIdentifier(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var result = exists(aRepository, SPARQLQueryBuilder.forClasses(aKB)
                .withIdentifier("http://example.org/#DoesNotExist"));

        assertThat(result).isFalse();
    }

    /**
     * Checks that item information can be obtained for a given subject.
     * 
     * @throws Exception
     *             -
     */
    static void thatCanRetrieveItemInfoForIdentifier(Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withIdentifier("http://example.org/#red-goblin") //
                .retrieveLabel() //
                .retrieveDescription());

        assertThat(results).isNotEmpty();
        assertThat(results).usingRecursiveFieldByFieldElementComparatorOnFields("identifier",
                "name", "description", "language")
                .containsExactlyInAnyOrder(KBHandle.builder()
                        .withIdentifier("http://example.org/#red-goblin").withName("Red Goblin")
                        .withDescription("Little red monster").build());
    }

    @SuppressWarnings("deprecation")
    static void thatAllPropertiesCanBeRetrieved(Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_PROPERTIES);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forProperties(aKB) //
                .retrieveLabel() //
                .retrieveDescription() //
                .retrieveDomainAndRange());

        assertThat(results).isNotEmpty();
        assertThat(results)
                .usingRecursiveFieldByFieldElementComparatorOnFields("identifier", "name",
                        "description", "range", "domain")
                .containsExactlyInAnyOrder(
                        new KBHandle("http://example.org/#property-1", "Property 1", "Property One",
                                null, "http://example.org/#explicitRoot",
                                "http://www.w3.org/2001/XMLSchema#string"),
                        new KBHandle("http://example.org/#property-2", "Property 2", "Property Two",
                                null, "http://example.org/#subclass1",
                                "http://www.w3.org/2001/XMLSchema#Integer"),
                        KBHandle.builder().withIdentifier("http://example.org/#property-3")
                                .withName("Property 3").withDescription("Property Three").build(),
                        KBHandle.builder().withIdentifier("http://example.org/#subproperty-1-1")
                                .withName("Subproperty 1-1").withDescription("Property One-One")
                                .build(),
                        KBHandle.builder().withIdentifier("http://example.org/#subproperty-1-1-1")
                                .withName("Subproperty 1-1-1")
                                .withDescription("Property One-One-One").build());
    }

    static void thatDeprecationStatusCanBeRetrieved(Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_DEPRECATED);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .retrieveLabel() //
                .retrieveDeprecation());

        assertThat(results).isNotEmpty();
        assertThat(results)
                .usingRecursiveFieldByFieldElementComparatorOnFields("identifier", "name",
                        "deprecated")
                .containsExactlyInAnyOrder(
                        KBHandle.builder().withIdentifier("http://example.org/#class-1")
                                .withName("Class 1").withDeprecated(true).build(),
                        KBHandle.builder().withIdentifier("http://example.org/#class-2")
                                .withName("Class 2").withDeprecated(false).build(),
                        KBHandle.builder().withIdentifier("http://example.org/#class-3")
                                .withName("Class 3").withDeprecated(true).build(),
                        KBHandle.builder().withIdentifier("http://example.org/#class-4")
                                .withName("Class 4").withDeprecated(false).build(),
                        KBHandle.builder().withIdentifier("http://example.org/#class-5")
                                .withName("Class 5").withDeprecated(true).build(),
                        KBHandle.builder().withIdentifier("http://example.org/#instance-1")
                                .withName("Instance 1").withDeprecated(true).build(),
                        KBHandle.builder().withIdentifier("http://example.org/#property-1")
                                .withName("Property 1").withDeprecated(true).build());
    }

    static void thatPropertyQueryLimitedToDescendantsDoesNotReturnOutOfScopeResults(
            Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_PROPERTIES);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forProperties(aKB) //
                .descendantsOf("http://example.org/#property-1"));

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getIdentifier) //
                .containsExactlyInAnyOrder("http://example.org/#subproperty-1-1",
                        "http://example.org/#subproperty-1-1-1");
    }

    static void thatPropertyQueryLimitedToChildrenDoesNotReturnOutOfScopeResults(
            Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_PROPERTIES);

        var results = asHandles(aRepository,
                SPARQLQueryBuilder.forProperties(aKB).childrenOf("http://example.org/#property-1"));

        assertThat(results).isNotEmpty();
        assertThat(results).extracting(KBHandle::getIdentifier)
                .containsExactlyInAnyOrder("http://example.org/#subproperty-1-1");
    }

    static void thatPropertyQueryLimitedToDomainDoesNotReturnOutOfScopeResults(
            Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_PROPERTIES);

        var results = asHandles(aRepository, SPARQLQueryBuilder.forProperties(aKB)
                .matchingDomain("http://example.org/#subclass1"));

        assertThat(results).isNotEmpty();
        assertThat(results).extracting(KBHandle::getIdentifier).containsExactlyInAnyOrder(
                // property-1 is inherited by #subclass1 from #explicitRoot
                "http://example.org/#property-1",
                // property-2 is declared on #subclass1
                "http://example.org/#property-2",
                // property-3 defines no domain
                "http://example.org/#property-3");
        // other properties all either define or inherit an incompatible domain
    }

    static void thatQueryLimitedToRootClassesDoesNotReturnOutOfScopeResults(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forClasses(aKB) //
                .roots());

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getUiLabel) //
                .containsExactlyInAnyOrder("explicitRoot", "implicitRoot");
    }

    static void thatQueryWithExplicitRootClassDoesNotReturnOutOfScopeResults(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        aKB.setRootConcepts(asList("http://example.org/#implicitRoot"));

        var results = asHandles(aRepository, SPARQLQueryBuilder.forClasses(aKB).roots());

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getUiLabel) //
                .containsExactlyInAnyOrder("implicitRoot");
    }

    static void thatNonRootClassCanBeUsedAsExplicitRootClass(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        aKB.setRootConcepts(
                asList("http://example.org/#implicitRoot", "http://example.org/#subclass2"));

        var results = asHandles(aRepository, SPARQLQueryBuilder.forClasses(aKB).roots());

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getUiLabel) //
                .containsExactlyInAnyOrder("implicitRoot", "subclass2");
    }

    static void thatQueryLimitedToClassesDoesNotReturnInstances(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var results = asHandles(aRepository, SPARQLQueryBuilder.forClasses(aKB));

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getUiLabel) //
                .noneMatch(label -> label.contains("instance"));
    }

    static void thatQueryLimitedToInstancesDoesNotReturnClasses(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var results = asHandles(aRepository, SPARQLQueryBuilder.forInstances(aKB));

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getUiLabel) //
                .noneMatch(label -> label.contains("class"));
    }

    static void thatClassQueryLimitedToAnchestorsDoesNotReturnOutOfScopeResults(
            Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forClasses(aKB) //
                .ancestorsOf("http://example.org/#subclass1-1-1"));

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getIdentifier) //
                .containsExactlyInAnyOrder("http://example.org/#explicitRoot",
                        "http://example.org/#subclass1", "http://example.org/#subclass1-1");
    }

    static void thatClassQueryLimitedToParentsDoesNotReturnOutOfScopeResults(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forClasses(aKB) //
                .parentsOf("http://example.org/#subclass1-1"));

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getIdentifier) //
                .containsExactlyInAnyOrder("http://example.org/#subclass1");
    }

    static void thatClassQueryLimitedToChildrenDoesNotReturnOutOfScopeResults(
            Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forClasses(aKB) //
                .childrenOf("http://example.org/#subclass1"));

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getIdentifier) //
                .containsExactlyInAnyOrder("http://example.org/#subclass1-1");
    }

    static void thatClassQueryLimitedToDescendantsDoesNotReturnOutOfScopeResults(
            Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forClasses(aKB) //
                .descendantsOf("http://example.org/#subclass1"));

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getIdentifier) //
                .containsExactlyInAnyOrder("http://example.org/#subclass1-1",
                        "http://example.org/#subclass1-1-1");
    }

    static void thatInstanceQueryLimitedToParentsDoesNotReturnOutOfScopeResults(
            Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forClasses(aKB) //
                .parentsOf("http://example.org/#1-1-1-instance-4"));

        assertThat(results).isNotEmpty(); //
        assertThat(results) //
                .extracting(KBHandle::getIdentifier) //
                .containsExactlyInAnyOrder("http://example.org/#subclass1-1-1");
    }

    static void thatInstanceQueryLimitedToAnchestorsDoesNotReturnOutOfScopeResults(
            Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forClasses(aKB) //
                .ancestorsOf("http://example.org/#1-1-1-instance-4"));

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getIdentifier) //
                .containsExactlyInAnyOrder("http://example.org/#explicitRoot",
                        "http://example.org/#subclass1", "http://example.org/#subclass1-1",
                        "http://example.org/#subclass1-1-1");
    }

    static void thatInstanceQueryLimitedToChildrenDoesNotReturnOutOfScopeResults(
            Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forInstances(aKB) //
                .childrenOf("http://example.org/#subclass1"));

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getIdentifier) //
                .containsExactlyInAnyOrder("http://example.org/#1-instance-1");
    }

    static void thatInstanceQueryLimitedToDescendantsDoesNotReturnOutOfScopeResults(
            Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forInstances(aKB) //
                .descendantsOf("http://example.org/#subclass1"));

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getIdentifier) //
                .allMatch(label -> label.matches("http://example.org/#1(-1)*-instance-.*"));
    }

    static void thatItemQueryLimitedToChildrenDoesNotReturnOutOfScopeResults(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .childrenOf("http://example.org/#subclass1"));

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getIdentifier) //
                .containsExactlyInAnyOrder( //
                        "http://example.org/#1-instance-1", "http://example.org/#subclass1-1");
    }

    static void thatItemQueryLimitedToDescendantsDoesNotReturnOutOfScopeResults(
            Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_CLASS_RDFS_HIERARCHY);

        var results = asHandles(aRepository, SPARQLQueryBuilder.forItems(aKB) //
                .descendantsOf("http://example.org/#subclass1"));

        assertThat(results).isNotEmpty();
        assertThat(results) //
                .extracting(KBHandle::getIdentifier) //
                .allMatch(label -> label.matches("http://example.org/#1(-1)*-instance-.*")
                        || label.startsWith("http://example.org/#subclass1-"));
    }

    static void testWithLabelMatchingAnyOf_withLanguage_noFTS(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        aKB.setFullTextSearchIri(null);

        testWithLabelMatchingAnyOf_withLanguage(aRepository, aKB);
    }

    static void testWithLabelMatchingAnyOf_withLanguage(Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE);

        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withPrefLabelProperties(prefLabels) //
                .withLabelMatchingAnyOf("Gobli"));

        assertThat(results).extracting(KBHandle::getUiLabel)
                .allMatch(label -> label.contains("Goblin"));
        assertThat(results).extracting(KBHandle::getIdentifier).doesNotHaveDuplicates();
        assertThat(results) //
                .usingRecursiveFieldByFieldElementComparatorOnFields( //
                        "identifier", "name", "language") //
                .containsExactlyInAnyOrder(
                        new KBHandle("http://example.org/#red-goblin", "Red Goblin"), //
                        new KBHandle("http://example.org/#green-goblin", "Green Goblin", null,
                                "en"));
    }

    static void testWithLabelContainingAnyOf_withLanguage_noFTS(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        aKB.setFullTextSearchIri(null);

        testWithLabelContainingAnyOf_withLanguage(aRepository, aKB);
    }

    static void testWithLabelMatchingAnyOf_withFallbackLanguages(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE);

        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withFallbackLanguages("it", "fr") //
                .withPrefLabelProperties(prefLabels) //
                .withLabelMatchingAnyOf("Blue"));

        assertThat(results).extracting(KBHandle::getUiLabel)
                .allMatch(label -> label.contains("Blu"));
        assertThat(results).extracting(KBHandle::getIdentifier).doesNotHaveDuplicates();
        assertThat(results) //
                .usingRecursiveFieldByFieldElementComparatorOnFields( //
                        "identifier", "name", "language") //
                .containsExactlyInAnyOrder(new KBHandle("http://example.org/#blue-goblin",
                        "Folletto Blue", null, "it"));
    }

    static void testWithLabelContainingAnyOf_withLanguage(Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE);

        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withPrefLabelProperties(prefLabels) //
                .withLabelContainingAnyOf("Goblin"));

        assertThat(results).extracting(KBHandle::getUiLabel)
                .allMatch(label -> label.contains("Goblin"));
        assertThat(results).extracting(KBHandle::getIdentifier).doesNotHaveDuplicates();
        assertThat(results).usingRecursiveFieldByFieldElementComparatorOnFields("identifier",
                "name", "language").containsExactlyInAnyOrder(
                        new KBHandle("http://example.org/#red-goblin", "Red Goblin"), new KBHandle(
                                "http://example.org/#green-goblin", "Green Goblin", null, "en"));
    }

    static void testWithLabelMatchingExactlyAnyOf_withLanguage_noFTS(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        aKB.setFullTextSearchIri(null);

        testWithLabelMatchingExactlyAnyOf_withLanguage(aRepository, aKB);
    }

    static void testWithLabelMatchingExactlyAnyOf_withLanguage(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE);

        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withPrefLabelProperties(prefLabels) //
                .withLabelMatchingExactlyAnyOf("Green Goblin"));

        assertThat(results).extracting(KBHandle::getUiLabel)
                .allMatch(label -> label.equals("Green Goblin"));
        assertThat(results).extracting(KBHandle::getIdentifier).doesNotHaveDuplicates();
        assertThat(results)
                .usingRecursiveFieldByFieldElementComparatorOnFields("identifier", "name",
                        "language")
                .containsExactlyInAnyOrder(new KBHandle("http://example.org/#green-goblin",
                        "Green Goblin", null, "en"));
    }

    static void testWithLabelMatchingExactlyAnyOf_subproperty_noFTS(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        aKB.setFullTextSearchIri(null);

        testWithLabelMatchingExactlyAnyOf_subproperty(aRepository, aKB);
    }

    static void testWithLabelMatchingExactlyAnyOf_subproperty(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        aKB.setSubPropertyIri(RDFS.SUBPROPERTYOF.stringValue());
        aKB.setLabelIri(RDFS.LABEL.stringValue());

        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, LABEL_SUBPROPERTY);

        // The label "Green Goblin" is not assigned directly via rdfs:label but rather via a
        // subproperty of it. Thus, this test also checks if the label sub-property support works.
        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withPrefLabelProperties(prefLabels) //
                .withLabelMatchingExactlyAnyOf("Green Goblin"));

        assertThat(results).extracting(KBHandle::getUiLabel)
                .allMatch(label -> label.equals("Green Goblin"));
        assertThat(results).extracting(KBHandle::getIdentifier).doesNotHaveDuplicates();
        assertThat(results).usingRecursiveFieldByFieldElementComparatorOnFields("identifier",
                "name", "language").containsExactlyInAnyOrder(
                        new KBHandle("http://example.org/#green-goblin", "Green Goblin"));
    }

    static void testWithLabelStartingWith_withoutLanguage_noFTS(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        aKB.setFullTextSearchIri(null);

        testWithLabelStartingWith_withoutLanguage(aRepository, aKB);
    }

    static void testWithLabelStartingWith_withoutLanguage(Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX, DATA_LABELS_WITHOUT_LANGUAGE);

        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withPrefLabelProperties(prefLabels) //
                .withLabelStartingWith("Green"));

        assertThat(results).extracting(KBHandle::getUiLabel)
                .allMatch(label -> label.startsWith("Green"));
        assertThat(results).extracting(KBHandle::getIdentifier).doesNotHaveDuplicates();
        assertThat(results).usingRecursiveFieldByFieldElementComparatorOnFields("identifier",
                "name", "language").containsExactlyInAnyOrder(
                        new KBHandle("http://example.org/#green-goblin", "Green Goblin"));
    }

    static void testWithLabelStartingWith_withLanguage_noFTS(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        aKB.setFullTextSearchIri(null);

        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE);

        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withPrefLabelProperties(prefLabels) //
                .withLabelStartingWith("Green Goblin"));

        assertThat(results).extracting(KBHandle::getUiLabel)
                .allMatch(label -> label.startsWith("Green"));
        assertThat(results).extracting(KBHandle::getIdentifier).doesNotHaveDuplicates();
        assertThat(results)
                .usingRecursiveFieldByFieldElementComparatorOnFields("identifier", "name",
                        "language")
                .containsExactlyInAnyOrder(new KBHandle("http://example.org/#green-goblin",
                        "Green Goblin", null, "en"));
    }

    static void testWithLabelStartingWith_withLanguage_FTS_1(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE);

        // Single word - actually, we add a wildcard here so anything that starts with "Green"
        // would also be matched
        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withPrefLabelProperties(prefLabels) //
                .withLabelStartingWith("Green"));

        assertThat(results).extracting(KBHandle::getUiLabel)
                .allMatch(label -> label.startsWith("Green"));
        assertThat(results).extracting(KBHandle::getIdentifier).doesNotHaveDuplicates();
        assertThat(results)
                .usingRecursiveFieldByFieldElementComparatorOnFields("identifier", "name",
                        "language")
                .containsExactlyInAnyOrder(new KBHandle("http://example.org/#green-goblin",
                        "Green Goblin", null, "en"));
    }

    static void testWithLabelStartingWith_withLanguage_FTS_2(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE);

        // Two words with the second being very short - this is no problem for the LUCENE FTS
        // and we simply add a wildcard to match "Green Go*"
        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withPrefLabelProperties(prefLabels) //
                .withLabelStartingWith("Green Go"));

        assertThat(results).extracting(KBHandle::getUiLabel)
                .allMatch(label -> label.startsWith("Green Go"));
        assertThat(results).extracting(KBHandle::getIdentifier).doesNotHaveDuplicates();
        assertThat(results)
                .usingRecursiveFieldByFieldElementComparatorOnFields("identifier", "name",
                        "description", "language")
                .containsExactlyInAnyOrder(new KBHandle("http://example.org/#green-goblin",
                        "Green Goblin", null, "en"));
    }

    static void testWithLabelStartingWith_withLanguage_FTS_3(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE);

        // Two words with the second being very short and a space following - in this case we
        // assume that the user is in fact searching for "Barack Ob" and do either drop the
        // last element nor add a wildcard
        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withPrefLabelProperties(prefLabels) //
                .withLabelStartingWith("Green Go "));

        assertThat(results).isEmpty();
    }

    static void testWithLabelStartingWith_withLanguage_FTS_4(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE);

        // Two words with the second being very short - this is no problem for the LUCENE FTS
        // and we simply add a wildcard to match "Green Go*"
        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withPrefLabelProperties(prefLabels) //
                .withLabelStartingWith("Green     Go"));

        assertThat(results).extracting(KBHandle::getUiLabel)
                .allMatch(label -> label.startsWith("Green Go"));
        assertThat(results).extracting(KBHandle::getIdentifier).doesNotHaveDuplicates();
        assertThat(results)
                .usingRecursiveFieldByFieldElementComparatorOnFields("identifier", "name",
                        "description", "language")
                .containsExactlyInAnyOrder(new KBHandle("http://example.org/#green-goblin",
                        "Green Goblin", null, "en"));
    }

    static void testWithLabelStartingWith_withLanguage_FTS_5(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromString(aRepository, aKB, TURTLE, TURTLE_PREFIX,
                DATA_LABELS_AND_DESCRIPTIONS_WITH_LANGUAGE);

        // language → expected UI label
        var expectations = Map.of( //
                "de", "Automobil", //
                "en", "automobile", //
                "fr", "automobile", //
                "es", "automóvil", //
                "it", "automobile", //
                "pt", "automóvel", //
                "nl", "auto");

        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        expectations.forEach((language, expectedLabel) -> {
            aKB.setDefaultLanguage(language);
            aKB.setAdditionalLanguages(expectations.keySet().stream() //
                    .filter(lang -> !language.equals(lang)) //
                    .toList());

            var results = asHandle(aRepository, SPARQLQueryBuilder //
                    .forItems(aKB) //
                    .withPrefLabelProperties(prefLabels) //
                    .withLabelContainingAnyOf("auto"));

            assertThat(results).as("Language: %s", language) //
                    .extracting(KBHandle::getUiLabel) //
                    .matches(expectedLabel::equals, "is equal to");
        });
    }

    static void testWithLabelStartingWith_customLabelProperty(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        aKB.setLabelIri("http://example.org/test/tagset#hasTag");

        importDataFromFile(aRepository, aKB, "src/test/resources/data/tagset_custom_label.ttl");

        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forInstances(aKB) //
                .withPrefLabelProperties(prefLabels) //
                .withLabelStartingWith("N"));

        // "Number" is only an rdfs:label, not a tag, so it must not match
        assertThat(results).extracting(KBHandle::getIdentifier).doesNotHaveDuplicates();
        assertThat(results).isNotEmpty();
        assertThat(results).extracting(KBHandle::getUiLabel).containsExactlyInAnyOrder("NN", "NNP",
                "NNPS", "NNS");
    }

    static void testWithLabelContainingAnyOf_pets_ttl_noFTS(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        aKB.setFullTextSearchIri(null);

        importDataFromFile(aRepository, aKB, "src/test/resources/data/pets.ttl");

        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forItems(aKB) //
                .withPrefLabelProperties(prefLabels) //
                .withLabelContainingAnyOf("Socke"));

        assertThat(results).extracting(KBHandle::getUiLabel)
                .allMatch(label -> label.contains("Socke"));
        assertThat(results).extracting(KBHandle::getIdentifier).doesNotHaveDuplicates();
        assertThat(results).usingRecursiveFieldByFieldElementComparatorOnFields("identifier",
                "name", "language")
                .containsExactlyInAnyOrder(new KBHandle("http://mbugert.de/pets#socke", "Socke"));
    }

    static void thatRootsCanBeRetrieved_owl(Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        importDataFromFile(aRepository, aKB, "src/test/resources/data/lexicon_ontology.ttl");

        initOwlMapping(aKB);

        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forClasses(aKB) //
                .roots() //
                .retrieveLabel());

        // Excluded: subclasses, the intersection class "Noun", the instance "dog" and the class
        // "Lemma", whose only superclass is an anonymous restriction. Included: the undeclared
        // external class "Morpheme", which is only used as a superclass.
        assertThat(results).extracting(KBHandle::getUiLabel).containsExactlyInAnyOrder(
                "Lexical entry", "Sense", "Concept", "Part of speech", "Morpheme");
    }

    static void thatIntersectionClassIsChildOfItsOperands_owl(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        importDataFromFile(aRepository, aKB, "src/test/resources/data/lexicon_ontology.ttl");

        initOwlMapping(aKB);

        for (var operand : List.of("http://example.org/test/lexicon#Word",
                "http://example.org/test/lexicon#OpenClass")) {
            var children = asHandles(aRepository, SPARQLQueryBuilder //
                    .forClasses(aKB) //
                    .childrenOf(operand) //
                    .retrieveLabel());

            assertThat(children).as("Children of %s", operand) //
                    .extracting(KBHandle::getUiLabel) //
                    .containsExactly("Noun");
        }

        var ancestors = asHandles(aRepository, SPARQLQueryBuilder //
                .forClasses(aKB) //
                .ancestorsOf("http://example.org/test/lexicon#Noun") //
                .retrieveLabel());

        // Known limitation: owl:intersectionOf is followed only one step, so the superclasses of
        // the operands ("Lexical entry", "Part of speech") are not returned as ancestors
        assertThat(ancestors).extracting(KBHandle::getUiLabel) //
                .containsExactlyInAnyOrder("Word", "Open class");
    }

    static void testWithLabelStartingWith_customLabelProperty_rdfXml(Repository aRepository,
            KnowledgeBase aKB)
        throws Exception
    {
        aKB.setLabelIri("http://example.org/test/tagset#hasTag");

        // RDF/XML with internal DOCTYPE entities, which the import must accept
        importDataFromFile(aRepository, aKB, "src/test/resources/data/tagset_custom_label.rdf");

        var prefLabels = resolvePrefLabelProperties(aRepository, aKB);
        var results = asHandles(aRepository, SPARQLQueryBuilder //
                .forInstances(aKB) //
                .withPrefLabelProperties(prefLabels) //
                .withLabelStartingWith("N"));

        assertThat(results).extracting(KBHandle::getIdentifier).doesNotHaveDuplicates();
        assertThat(results).extracting(KBHandle::getUiLabel).containsExactlyInAnyOrder("NN", "NNP",
                "NNPS", "NNS");
    }

    static Set<String> resolvePrefLabelProperties(Repository aRepository, KnowledgeBase aKB)
        throws Exception
    {
        try (var conn = aRepository.getConnection()) {
            return SPARQLQueryBuilder.forItems(aKB).resolvePrefLabelProperties(conn);
        }
    }

    static void importDataFromFile(Repository aRepository, KnowledgeBase aKB, String aFilename)
        throws IOException
    {
        // Detect the file format
        var format = Rio.getParserFormatForFileName(aFilename).orElse(RDFXML);

        LOG.info("Loading {} data from {}", format, aFilename);

        // Load files into the repository
        try (var is = new FileInputStream(aFilename)) {
            importData(aRepository, aKB, format, is);
        }
    }

    static void importDataFromString(Repository aRepository, KnowledgeBase aKB, RDFFormat aFormat,
            String... aRdfData)
        throws IOException
    {
        var data = String.join("\n", aRdfData);

        // Load files into the repository
        try (var is = IOUtils.toInputStream(data, UTF_8)) {
            importData(aRepository, aKB, aFormat, is);
        }
    }

    static void importData(Repository aRepository, KnowledgeBase aKB, RDFFormat aFormat,
            InputStream aIS)
        throws IOException
    {
        try (var conn = aRepository.getConnection()) {
            conn.setParserConfig(doctypeTolerantParserConfig());
            // If the RDF file contains relative URLs, then they probably start with a hash.
            // To avoid having two hashes here, we drop the hash from the base prefix configured
            // by the user.
            var prefix = StringUtils.removeEnd(aKB.getBasePrefix(), "#");
            if (aKB.getDefaultDatasetIri() != null) {
                var ctx = SimpleValueFactory.getInstance().createIRI(aKB.getDefaultDatasetIri());
                conn.add(aIS, prefix, aFormat, ctx);
            }
            else {
                conn.add(aIS, prefix, aFormat);
            }
        }
    }

    static void initRdfsMapping(KnowledgeBase aKB)
    {
        aKB.setClassIri(RDFS.CLASS.stringValue());
        aKB.setSubclassIri(RDFS.SUBCLASSOF.stringValue());
        aKB.setTypeIri(RDF.TYPE.stringValue());
        aKB.setLabelIri(RDFS.LABEL.stringValue());
        aKB.setPropertyTypeIri(RDF.PROPERTY.stringValue());
        aKB.setDescriptionIri(RDFS.COMMENT.stringValue());
        // We are intentionally not using RDFS.LABEL here to ensure we can test the label
        // and property label separately
        aKB.setPropertyLabelIri(SKOS.PREF_LABEL.stringValue());
        // We are intentionally not using RDFS.COMMENT here to ensure we can test the description
        // and property description separately
        aKB.setPropertyDescriptionIri("http://schema.org/description");
        aKB.setSubPropertyIri(RDFS.SUBPROPERTYOF.stringValue());
        aKB.setDeprecationPropertyIri(OWL.DEPRECATED.stringValue());
    }

    static void initOwlMapping(KnowledgeBase aKB)
    {
        aKB.setClassIri(OWL.CLASS.stringValue());
        aKB.setSubclassIri(RDFS.SUBCLASSOF.stringValue());
        aKB.setTypeIri(RDF.TYPE.stringValue());
        aKB.setLabelIri(RDFS.LABEL.stringValue());
        aKB.setPropertyTypeIri(RDF.PROPERTY.stringValue());
        aKB.setDescriptionIri(RDFS.COMMENT.stringValue());
        aKB.setPropertyLabelIri(RDF.PROPERTY.stringValue());
        aKB.setPropertyDescriptionIri(RDFS.COMMENT.stringValue());
        aKB.setSubPropertyIri(RDFS.SUBPROPERTYOF.stringValue());
        aKB.setDeprecationPropertyIri(OWL.DEPRECATED.stringValue());
    }

    static void initWikidataMapping(KnowledgeBase aKB)
    {
        aKB.setClassIri("http://www.wikidata.org/entity/Q35120");
        aKB.setSubclassIri("http://www.wikidata.org/prop/direct/P279");
        aKB.setTypeIri("http://www.wikidata.org/prop/direct/P31");
        aKB.setLabelIri("http://www.w3.org/2000/01/rdf-schema#label");
        aKB.setPropertyTypeIri("http://www.wikidata.org/entity/Q18616576");
        aKB.setDescriptionIri("http://schema.org/description");
        aKB.setPropertyLabelIri("http://www.w3.org/2000/01/rdf-schema#label");
        aKB.setPropertyDescriptionIri("http://www.w3.org/2000/01/rdf-schema#comment");
        aKB.setSubPropertyIri("http://www.wikidata.org/prop/direct/P1647");
    }

    public static void assertIsReachable(Repository aRepository)
    {
        if (aRepository instanceof SPARQLRepository sparqlRepository) {
            assumeTrue(isReachable(sparqlRepository.toString()),
                    "Remote repository at [" + sparqlRepository + "] is not reachable");
        }
    }
}
