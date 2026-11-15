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
package de.tudarmstadt.ukp.inception.kb.util;

import static de.tudarmstadt.ukp.inception.kb.RdfImportParserConfigs.doctypeTolerantParserConfig;
import static de.tudarmstadt.ukp.inception.kb.IriConstants.FTS_RDF4J_LUCENE;
import static de.tudarmstadt.ukp.inception.kb.IriConstants.INCEPTION_NAMESPACE;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.newPerThreadSslCheckingHttpClientBuilder;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.codec.net.URLCodec;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.SKOS;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.lucene.LuceneSail;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.inception.kb.RepositoryType;
import de.tudarmstadt.ukp.inception.kb.graph.KBConcept;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.kb.graph.KBInstance;
import de.tudarmstadt.ukp.inception.kb.graph.KBProperty;
import de.tudarmstadt.ukp.inception.kb.graph.KBQualifier;
import de.tudarmstadt.ukp.inception.kb.graph.KBStatement;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.kb.reification.Reification;
import de.tudarmstadt.ukp.inception.kb.yaml.KnowledgeBaseProfile;

public class TestFixtures
{
    private static final int MAX_REDIRECTS = 10;

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final Set<String> UNREACHABLE_ENDPOINTS = new LinkedHashSet<>();

    private TestEntityManager entityManager;

    public TestFixtures(TestEntityManager aEntityManager)
    {
        entityManager = aEntityManager;
    }

    public Project createProject(String name)
    {
        Project project = new Project();
        project.setName(name);
        return entityManager.persist(project);
    }

    public KnowledgeBase buildKnowledgeBase(Project project, String name, Reification reification)
    {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setProject(project);
        kb.setType(RepositoryType.LOCAL);
        kb.setClassIri(RDFS.CLASS.stringValue());
        kb.setSubclassIri(RDFS.SUBCLASSOF.stringValue());
        kb.setTypeIri(RDF.TYPE.stringValue());
        kb.setLabelIri(RDFS.LABEL.stringValue());
        kb.setPropertyTypeIri(RDF.PROPERTY.stringValue());
        kb.setDescriptionIri(RDFS.COMMENT.stringValue());
        kb.setSubPropertyIri(RDFS.SUBPROPERTYOF.stringValue());
        kb.setFullTextSearchIri(FTS_RDF4J_LUCENE.stringValue());
        // Intentionally using different IRIs for label/description and property-label/description
        // to detect cases where we accidentally construct queries using the wrong mapping, e.g.
        // querying for properties with the class label.
        kb.setPropertyLabelIri(SKOS.PREF_LABEL.stringValue());
        kb.setPropertyDescriptionIri(SKOS.DEFINITION.stringValue());
        kb.setDeprecationPropertyIri(OWL.DEPRECATED.stringValue());
        kb.setRootConcepts(new ArrayList<>());
        kb.setReification(reification);
        kb.setMaxResults(1000);
        kb.setDefaultLanguage("en");
        return kb;
    }

    public static KnowledgeBase buildKnowledgeBase(KnowledgeBaseProfile profile)
    {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setDefaultLanguage(profile.getDefaultLanguage());
        kb.setType(profile.getType());
        kb.setFullTextSearchIri(profile.getAccess().getFullTextSearchIri());
        kb.setDefaultDatasetIri(profile.getDefaultDataset());
        kb.setMaxResults(1000);
        kb.applyMapping(profile.getMapping());
        kb.applyRootConcepts(profile);

        return kb;
    }

    public static Repository buildRepository(KnowledgeBaseProfile profile) throws IOException
    {
        switch (profile.getType()) {
        case LOCAL: {
            LuceneSail lucenesail = new LuceneSail();
            lucenesail.setParameter(LuceneSail.LUCENE_RAMDIR_KEY, "true");
            lucenesail.setBaseSail(new MemoryStore());
            Repository repo = new SailRepository(lucenesail);
            repo.init();
            importData(repo, INCEPTION_NAMESPACE, profile.getAccess().getAccessUrl());
            return repo;
        }
        case REMOTE: {
            assumeTrue(isReachable(profile.getAccess().getAccessUrl()), "Remote repository at ["
                    + profile.getAccess().getAccessUrl() + "] is not reachable");

            var repo = new SPARQLRepository(profile.getAccess().getAccessUrl());
            // Public endpoints such as AGROVOC rate-limit, so back off and retry instead of failing
            repo.setHttpClient(newPerThreadSslCheckingHttpClientBuilder() //
                    .setServiceUnavailableRetryStrategy(
                            new RateLimitRetryStrategy(4, 5_000, 60_000)) //
                    .build());
            repo.setAdditionalHttpHeaders(Map.of("User-Agent", "INCEpTION/0.0.1-SNAPSHOT"));
            repo.init();
            return repo;
        }
        default:
            throw new IllegalStateException("Unknown repo type: " + profile.getType());
        }
    }

    public KBConcept buildConcept()
    {
        KBConcept concept = new KBConcept();
        concept.setName("Concept name");
        concept.setDescription("Concept description");
        return concept;
    }

    public KBConcept buildConceptWithLanguage(String aLanguage)
    {
        KBConcept concept = buildConcept();
        concept.setLanguage(aLanguage);
        return concept;
    }

    public KBProperty buildProperty()
    {
        KBProperty property = new KBProperty();
        property.setDescription("Property description");
        property.setDomain("https://test.schema.com/#domain");
        property.setName("Property name");
        property.setRange("https://test.schema.com/#range");
        property.setLanguage("en");
        return property;
    }

    public KBProperty buildPropertyWithLanguage(String aLanguage)
    {
        KBProperty property = buildProperty();
        property.setLanguage(aLanguage);
        return property;
    }

    public KBInstance buildInstance()
    {
        KBInstance instance = new KBInstance();
        instance.setName("Instance name");
        instance.setDescription("Instance description");
        instance.setType(URI.create("https://test.schema.com/#type"));
        instance.setLanguage("en");
        return instance;
    }

    public KBInstance buildInstanceWithLanguage(String aLanguage)
    {
        KBInstance instance = buildInstance();
        instance.setLanguage(aLanguage);
        return instance;
    }

    public KBStatement buildStatement(KBHandle conceptHandle, KBProperty aProperty, String value)
    {
        ValueFactory vf = SimpleValueFactory.getInstance();
        KBStatement statement = new KBStatement(null, conceptHandle, aProperty,
                vf.createLiteral(value));
        return statement;
    }

    public KBQualifier buildQualifier(KBStatement kbStatement, KBProperty propertyHandle,
            String value)
    {
        ValueFactory vf = SimpleValueFactory.getInstance();
        KBQualifier qualifier = new KBQualifier(kbStatement, propertyHandle,
                vf.createLiteral(value));
        return qualifier;
    }

    public static boolean isReachable(String aUrl)
    {
        if (UNREACHABLE_ENDPOINTS.contains(aUrl)) {
            return false;
        }

        try {
            var url = new URL(aUrl + "?query="
                    + new URLCodec().encode("SELECT ?v WHERE { BIND (true AS ?v)}"));
            var con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("HEAD");
            con.setConnectTimeout(7_500);
            con.setReadTimeout(7_500);
            con.setRequestProperty("Content-Type", "application/sparql-query");
            con.setRequestProperty("User-Agent", "INCEpTION/0.0.1-SNAPSHOT");
            var status = con.getResponseCode();

            if (status == HTTP_MOVED_TEMP || status == HTTP_MOVED_PERM) {
                // Strip any query string from the Location header before recursing: the server
                // may echo back the ?query= parameter we sent, and appending it again in the
                // recursive call would result in "Multiple 'query=' parameters" errors.
                var location = con.getHeaderField("Location");
                var qIdx = location.indexOf('?');
                if (qIdx >= 0) {
                    location = location.substring(0, qIdx);
                }
                return isReachable(location);
            }
        }
        catch (Exception e) {
            LOG.error("[{}] Network-level check: {}", aUrl, e.getMessage());
            UNREACHABLE_ENDPOINTS.add(aUrl);
            return false;
        }

        var r = new SPARQLRepository(aUrl);
        r.setHttpClient(newPerThreadSslCheckingHttpClientBuilder().build());
        r.setAdditionalHttpHeaders(Map.of("User-Agent", "INCEpTION/0.0.1-SNAPSHOT"));
        r.init();
        try (var conn = r.getConnection()) {
            var query = conn.prepareTupleQuery("SELECT ?v WHERE { BIND (true AS ?v)}");
            query.setMaxExecutionTime(5);
            try (var result = query.evaluate()) {
                return true;
            }
        }
        catch (Exception e) {
            LOG.error("[{}] Repository-level check: {}", aUrl, e.getMessage());
            UNREACHABLE_ENDPOINTS.add(aUrl);
            return false;
        }
    }

    /**
     * Tries to connect to the given endpoint url and assumes that the connection is successful with
     * {@link org.junit.jupiter.api.Assumptions#assumeTrue(boolean, String)}
     * 
     * @param aEndpointURL
     *            the url to check
     */
    public static void assumeEndpointIsAvailable(String aEndpointURL)
    {
        assumeTrue(isReachable(aEndpointURL),
                "Remote repository at [" + aEndpointURL + "] is not reachable");
    }

    @SuppressWarnings("resource")
    private static void importData(Repository aRepo, String aBasePrefix, String aUrl)
        throws IOException
    {
        try (InputStream aIS = openAsStream(aUrl)) {
            InputStream is = new BufferedInputStream(aIS);
            try {
                // Stream is expected to be closed by caller of importData
                is = new CompressorStreamFactory().createCompressorInputStream(is);
            }
            catch (CompressorException e) {
                // Probably not compressed then or unknown format - just try as is.
            }

            // Detect the file format
            RDFFormat format = Rio.getParserFormatForFileName(aUrl).orElse(RDFFormat.RDFXML);

            try (RepositoryConnection conn = aRepo.getConnection()) {
                conn.setParserConfig(doctypeTolerantParserConfig());
                // If the RDF file contains relative URLs, then they probably start with a hash.
                // To avoid having two hashes here, we drop the hash from the base prefix configured
                // by the user.
                String prefix = StringUtils.removeEnd(aBasePrefix, "#");
                conn.add(is, prefix, format);
            }
        }
    }

    private static InputStream openAsStream(String aUrl) throws IOException
    {
        if (aUrl.startsWith("classpath")) {
            return new PathMatchingResourcePatternResolver().getResource(aUrl).getInputStream();
        }

        var url = URI.create(aUrl).toURL();
        if (!url.getProtocol().startsWith("http")) {
            return url.openStream();
        }

        // HttpURLConnection does not follow redirects that change the protocol, e.g. from http to
        // https, which is common for PURLs. So we follow redirects ourselves. If the server cannot
        // be reached, the test is skipped like it is for unreachable remote repositories.
        for (var hop = 0; hop < MAX_REDIRECTS; hop++) {
            HttpURLConnection con;
            int status;
            try {
                con = (HttpURLConnection) url.openConnection();
                con.setInstanceFollowRedirects(false);
                con.setConnectTimeout(7_500);
                con.setReadTimeout(30_000);
                con.setRequestProperty("User-Agent", "INCEpTION/0.0.1-SNAPSHOT");
                status = con.getResponseCode();
            }
            catch (IOException e) {
                assumeTrue(false, "Data at [" + aUrl + "] is not reachable: " + e.getMessage());
                throw e;
            }

            var location = con.getHeaderField("Location");
            if (status >= 300 && status < 400 && location != null) {
                con.disconnect();
                url = URI.create(url.toString()).resolve(location).toURL();
                continue;
            }

            if (status == HttpURLConnection.HTTP_OK) {
                return con.getInputStream();
            }

            var proxyError = con.getHeaderField("X-Proxy-Error");
            con.disconnect();

            // Temporary conditions on the server or the network skip the test. Anything else, e.g.
            // a 404 because the URL in a profile is no longer valid, is a real failure.
            var temporary = status == 429 || status >= 500
                    || (status == HttpURLConnection.HTTP_FORBIDDEN && proxyError != null);
            assumeTrue(!temporary,
                    "Data at [" + aUrl + "] is temporarily not available (HTTP " + status + ")");
            throw new IOException("Unable to download [" + aUrl + "] (HTTP " + status + ")");
        }

        throw new IOException("Too many redirects when downloading [" + aUrl + "]");
    }
}
