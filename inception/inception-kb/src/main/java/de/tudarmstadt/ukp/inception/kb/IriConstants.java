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

import static java.util.Arrays.asList;

import java.util.List;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.SKOS;
import org.eclipse.rdf4j.model.vocabulary.XSD;

import de.tudarmstadt.ukp.inception.kb.graph.KBObject;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;

public class IriConstants
{
    public static final String INCEPTION_SCHEMA_NAMESPACE = "http://www.ukp.informatik.tu-darmstadt.de/inception/schema-1.0#";
    public static final String INCEPTION_NAMESPACE = "http://www.ukp.informatik.tu-darmstadt.de/inception/1.0#";

    public static final String PREFIX_WIKIDATA_ENTITY = "http://www.wikidata.org/entity/";
    public static final String PREFIX_WIKIDATA_DIRECT = "http://www.wikidata.org/prop/direct/";
    public static final String PREFIX_SCHEMA = "http://schema.org/";
    public static final String PREFIX_VIRTUOSO = "bif:";
    public static final String PREFIX_RDF4J_LUCENE_SEARCH = "http://www.openrdf.org/contrib/lucenesail#";
    public static final String PREFIX_ALLEGRO_GRAPH_FTI = "http://franz.com/ns/allegrograph/2.2/textindex/";
    public static final String PREFIX_MWAPI = "https://www.mediawiki.org/ontology#API/";
    public static final String PREFIX_STARDOG = "tag:stardog:api:search:";
    public static final String PREFIX_BLAZEGRAPH = "http://www.bigdata.com/rdf/search#";
    public static final String PREFIX_GRAPHDB = "http://www.ontotext.com/";
    public static final String PREFIX_QLEVER = "http://qlever.cs.uni-freiburg.de/builtin-functions/";
    public static final String PREFIX_MARKLOGIC = "http://marklogic.com/cts#";

    public static final Set<String> IMPLICIT_NAMESPACES = Set.of(RDF.NAMESPACE, RDFS.NAMESPACE,
            XSD.NAMESPACE, OWL.NAMESPACE, INCEPTION_SCHEMA_NAMESPACE);

    /**
     * http://www.wikidata.org/entity/Q35120
     */
    public static final IRI WIKIDATA_CLASS;

    /**
     * http://www.wikidata.org/prop/direct/P279
     */
    public static final IRI WIKIDATA_SUBCLASS;

    /**
     * http://www.wikidata.org/prop/direct/P31
     */
    public static final IRI WIKIDATA_TYPE;

    /**
     * http://www.wikidata.org/entity/Q18616576
     */
    public static final IRI WIKIDATA_PROPERTY_TYPE;

    /**
     * http://www.wikidata.org/prop/direct/P1647
     */
    public static final IRI WIKIDATA_SUBPROPERTY;

    /**
     * http://www.schema.org/description
     */
    public static final IRI SCHEMA_DESCRIPTION;

    public static final IRI FTS_FUSEKI;
    public static final IRI FTS_RDF4J_LUCENE;
    public static final IRI FTS_ALLEGRO_GRAPH;
    public static final IRI FTS_VIRTUOSO;
    public static final IRI FTS_WIKIDATA;
    public static final IRI FTS_STARDOG;
    public static final IRI FTS_BLAZEGRAPH;
    public static final IRI FTS_GRAPHDB;
    public static final IRI FTS_QLEVER;
    public static final IRI FTS_MARKLOGIC;
    public static final IRI FTS_NONE;

    public static final List<IRI> CLASS_IRIS;
    public static final List<IRI> SUBCLASS_IRIS;
    public static final List<IRI> TYPE_IRIS;
    public static final List<IRI> SUBPROPERTY_IRIS;
    public static final List<IRI> DESCRIPTION_IRIS;
    public static final List<IRI> LABEL_IRIS;
    public static final List<IRI> PROPERTY_TYPE_IRIS;
    public static final List<IRI> PROPERTY_LABEL_IRIS;
    public static final List<IRI> PROPERTY_DESCRIPTION_IRIS;
    public static final List<IRI> DEPRECATION_PROPERTY_IRIS;
    public static final List<IRI> FTS_IRIS;

    static {
        var vf = SimpleValueFactory.getInstance();

        WIKIDATA_CLASS = vf.createIRI(PREFIX_WIKIDATA_ENTITY, "Q35120");
        WIKIDATA_SUBCLASS = vf.createIRI(PREFIX_WIKIDATA_DIRECT, "P279");
        WIKIDATA_TYPE = vf.createIRI(PREFIX_WIKIDATA_DIRECT, "P31");
        WIKIDATA_PROPERTY_TYPE = vf.createIRI(PREFIX_WIKIDATA_ENTITY, "Q18616576");
        WIKIDATA_SUBPROPERTY = vf.createIRI(PREFIX_WIKIDATA_DIRECT, "P1647");
        SCHEMA_DESCRIPTION = vf.createIRI(PREFIX_SCHEMA, "description");

        FTS_FUSEKI = vf.createIRI("text:query");
        FTS_VIRTUOSO = vf.createIRI("bif:contains");
        FTS_ALLEGRO_GRAPH = vf.createIRI(PREFIX_ALLEGRO_GRAPH_FTI, "match");
        FTS_RDF4J_LUCENE = vf.createIRI(PREFIX_RDF4J_LUCENE_SEARCH, "matches");
        FTS_WIKIDATA = vf.createIRI(PREFIX_MWAPI, "search");
        FTS_STARDOG = vf.createIRI(PREFIX_STARDOG, "textMatch");
        FTS_BLAZEGRAPH = vf.createIRI(PREFIX_BLAZEGRAPH, "search");
        FTS_GRAPHDB = vf.createIRI(PREFIX_GRAPHDB, "fts");
        FTS_QLEVER = vf.createIRI(PREFIX_QLEVER, "contains-word");
        FTS_MARKLOGIC = vf.createIRI(PREFIX_MARKLOGIC, "contains");
        FTS_NONE = vf.createIRI("FTS:NONE");

        CLASS_IRIS = asList(RDFS.CLASS, OWL.CLASS, WIKIDATA_CLASS, SKOS.CONCEPT);
        SUBCLASS_IRIS = asList(RDFS.SUBCLASSOF, WIKIDATA_SUBCLASS, SKOS.BROADER);
        TYPE_IRIS = asList(RDF.TYPE, WIKIDATA_TYPE);
        SUBPROPERTY_IRIS = asList(RDFS.SUBPROPERTYOF, WIKIDATA_SUBPROPERTY);
        DESCRIPTION_IRIS = asList(RDFS.COMMENT, SCHEMA_DESCRIPTION);
        LABEL_IRIS = asList(RDFS.LABEL, SKOS.PREF_LABEL);
        PROPERTY_TYPE_IRIS = asList(RDF.PROPERTY, WIKIDATA_PROPERTY_TYPE);
        PROPERTY_LABEL_IRIS = asList(RDFS.LABEL, SKOS.PREF_LABEL);
        PROPERTY_DESCRIPTION_IRIS = asList(RDFS.COMMENT, SCHEMA_DESCRIPTION);
        DEPRECATION_PROPERTY_IRIS = asList(OWL.DEPRECATED);
        FTS_IRIS = asList(FTS_FUSEKI, FTS_BLAZEGRAPH, FTS_GRAPHDB, FTS_VIRTUOSO, FTS_WIKIDATA,
                FTS_RDF4J_LUCENE, FTS_STARDOG, FTS_ALLEGRO_GRAPH, FTS_QLEVER, FTS_MARKLOGIC);
    }

    public static String getFtsBackendName(String aFTS)
    {
        if (FTS_FUSEKI.stringValue().equals(aFTS)) {
            return "Apache Jena Fuseki";
        }

        if (FTS_BLAZEGRAPH.stringValue().equals(aFTS)) {
            return "Blazegraph DB";
        }

        if (FTS_GRAPHDB.stringValue().equals(aFTS)) {
            return "GraphDB";
        }

        if (FTS_VIRTUOSO.stringValue().equals(aFTS)) {
            return "Virtuoso";
        }

        if (FTS_WIKIDATA.stringValue().equals(aFTS)) {
            return "Wikidata (MediaWiki API Query Service EntitySearch)";
        }

        if (FTS_RDF4J_LUCENE.stringValue().equals(aFTS)) {
            return "RDF4J Lucene";
        }

        if (FTS_STARDOG.stringValue().equals(aFTS)) {
            return "Stardog";
        }

        if (FTS_ALLEGRO_GRAPH.stringValue().equals(aFTS)) {
            return "AllegroGraph";
        }

        if (FTS_QLEVER.stringValue().equals(aFTS)) {
            return "QLever (SPARQL+Text)";
        }

        if (FTS_MARKLOGIC.stringValue().equals(aFTS)) {
            return "MarkLogic";
        }

        return aFTS;
    }

    public static boolean hasImplicitNamespace(KnowledgeBase kb, String s)
    {
        // Root concepts are never implicit. E.g. if the root concept is owl:Thing, we do not
        // want to filter it out just because we consider the OWL namespace to be implicit.
        if (kb.getRootConcepts().contains(s)) {
            return false;
        }

        for (String ns : IMPLICIT_NAMESPACES) {
            if (s.startsWith(ns)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isFromImplicitNamespace(KBObject handle)
    {
        return IMPLICIT_NAMESPACES.stream().anyMatch(ns -> handle.getIdentifier().startsWith(ns));
    }
}
