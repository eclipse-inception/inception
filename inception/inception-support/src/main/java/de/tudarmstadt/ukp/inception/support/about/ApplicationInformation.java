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
package de.tudarmstadt.ukp.inception.support.about;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.list;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.inception.support.json.JSONUtil;

public class ApplicationInformation
{
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationInformation.class);

    private static final Map<String, Set<String>> SOURCES = new HashMap<>();
    private static final Map<String, Set<String>> SOURCE_BY_PACKAGE_NAME = new HashMap<>();

    static {
        SOURCE_BY_PACKAGE_NAME.put("emotion", Set.of( //
                "@emotion/.*"));
        SOURCE_BY_PACKAGE_NAME.put("Sven Sauleau", Set.of( //
                "@webassemblyjs/.*"));
        SOURCE_BY_PACKAGE_NAME.put("Mozilla Foundation", Set.of( //
                "pdfjs-dist"));

        SOURCES.put("Apache Software Foundation", Set.of( //
                ".*Apache Software Foundation.*"));
        SOURCES.put("Mozilla Foundation", Set.of( //
                ".*Mozilla Foundation.*"));
        SOURCES.put("Ubiquitous Knowledge Processing (UKP) Lab, Technische Universität Darmstadt",
                Set.of( //
                        ".*Ubiquitous Knowledge Processing.*"));
        SOURCES.put("webpack Contrib Team", Set.of( //
                "webpack Contrib"));
        SOURCES.put("FasterXML", Set.of( //
                "fasterxml\\.com"));
        SOURCES.put("Max Ogden", Set.of( //
                "max ogden"));
        SOURCES.put("Oracle Corporation", Set.of( //
                "Oracle"));
    }

    public static String sourceFromPackageName(String aString)
    {
        if (aString == null) {
            return null;
        }

        for (var entry : SOURCE_BY_PACKAGE_NAME.entrySet()) {
            if (entry.getValue().stream().anyMatch(pattern -> Pattern.matches(pattern, aString))) {
                return entry.getKey();
            }
        }

        return aString;
    }

    /**
     * @see KnownLicenses#toDisplayName(String)
     */
    public static String normaliseLicense(String aString)
    {
        return KnownLicenses.toDisplayName(aString);
    }

    public static String normaliseSource(String aString)
    {
        if (aString == null) {
            return null;
        }

        for (var entry : SOURCES.entrySet()) {
            if (entry.getValue().stream().anyMatch(pattern -> Pattern.matches(pattern, aString))) {
                return entry.getKey();
            }
        }

        return aString;
    }

    public static Set<Dependency> loadJsonDependencies()
    {
        try {
            var deps = new LinkedHashSet<Dependency>();
            var cl = ApplicationInformation.class.getClassLoader();

            for (URL mavenDeps : list(cl.getResources("META-INF/DEPENDENCIES.json"))) {
                loadMavenDependencies(mavenDeps).getDependencies()
                        .forEach(dep -> deps.add(dep.toDependency()));
            }

            for (URL npmDeps : list(cl.getResources("META-INF/NPM-DEPENDENCIES.json"))) {
                loadNpmDependencies(npmDeps).values() //
                        .forEach(dep -> deps.add(dep.toDependency()));
            }

            for (URL embeddedDeps : list(
                    cl.getResources("META-INF/DEPENDENCIES-EMBEDDED.spdx.json"))) {
                loadEmbeddedSpdxDependencies(embeddedDeps).getPackages() //
                        .forEach(dep -> deps.add(dep.toDependency()));
            }

            return deps;
        }
        catch (Exception e) {
            LOG.error("Unable to retrieve JSON dependency information", e);
            return Collections.emptySet();
        }
    }

    static EmbeddedSpdxDependencies loadEmbeddedSpdxDependencies(URL aDeps) throws IOException
    {
        try (InputStream is = aDeps.openStream()) {
            return JSONUtil.fromJsonStream(EmbeddedSpdxDependencies.class, is);
        }
        catch (Exception e) {
            LOG.error("Unable to read [{}]", aDeps, e);
            throw e;
        }
    }

    static NpmDependencies loadNpmDependencies(URL npmDeps) throws IOException
    {
        try (InputStream is = npmDeps.openStream()) {
            var deps = JSONUtil.fromJsonStream(NpmDependencies.class, is);
            for (var entry : deps.entrySet()) {
                entry.getValue().setName(entry.getKey());
            }
            return deps;
        }
    }

    static MavenDependencies loadMavenDependencies(URL mavenDeps) throws IOException
    {
        try (InputStream is = mavenDeps.openStream()) {
            return JSONUtil.fromJsonStream(MavenDependencies.class, is);
        }
    }

    public static String loadDependencies()
    {
        try {
            List<URL> candidates = list(ApplicationInformation.class.getClassLoader()
                    .getResources("META-INF/DEPENDENCIES.txt"));

            Optional<URL> webappDependencies = candidates.stream() //
                    .filter(url -> //
                    url.toString().contains("/WEB-INF/classes!/META-INF/DEPENDENCIES.txt") || //
                            url.toString().contains("target/classes/META-INF/DEPENDENCIES.txt"))
                    .findFirst();

            if (webappDependencies.isEmpty()) {
                return "Unable to locate dependency information";
            }

            try (InputStream is = webappDependencies.get().openStream()) {
                return IOUtils.toString(is, UTF_8);
            }
        }
        catch (Exception e) {
            LOG.error("Unable to retrieve dependency information", e);
            return "Unable to retrieve dependency information";
        }
    }
}
