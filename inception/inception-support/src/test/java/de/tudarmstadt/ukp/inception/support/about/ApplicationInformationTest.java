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

import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.loadJsonDependencies;
import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.loadMavenDependencies;
import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.loadNpmDependencies;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationInformationTest
{
    private static final String MAVEN_DEPS = "src/test/resources/ApplicationInformationTest/DEPENDENCIES.json";
    private static final String NPM_DEPS = "src/test/resources/ApplicationInformationTest/NPM-DEPENDENCIES.json";

    @TempDir
    Path tempDir;

    @Test
    void testLoadMavenDependencies() throws Exception
    {
        loadMavenDependencies(new File(MAVEN_DEPS).toURI().toURL());
    }

    @Test
    void testLoadNpmDependencies() throws Exception
    {
        loadNpmDependencies(new File(NPM_DEPS).toURI().toURL());
    }

    @Test
    void thatOnlyTheApplicationsMavenDependenciesAreListed() throws Exception
    {
        var application = classpathRoot("application", //
                dependency("library-a", "2.0"));
        Files.writeString(application.resolve("META-INF/version.properties"), "version=1.0");
        var thirdParty = classpathRoot("third-party", //
                dependency("library-a", "1.0"), //
                dependency("library-b", "1.0"));

        try (var cl = classLoader(application, thirdParty)) {
            assertThat(loadJsonDependencies(cl)) //
                    .extracting(Dependency::getName, Dependency::getVersion) //
                    .containsExactly(tuple("library-a", "2.0"));
        }
    }

    @Test
    void thatAllMavenDependenciesAreMergedWithoutApplicationRoot() throws Exception
    {
        var first = classpathRoot("first", //
                dependency("library-a", "2.0"));
        var second = classpathRoot("second", //
                dependency("library-b", "1.0"));

        try (var cl = classLoader(first, second)) {
            assertThat(loadJsonDependencies(cl)) //
                    .extracting(Dependency::getName) //
                    .containsExactlyInAnyOrder("library-a", "library-b");
        }
    }

    private Path classpathRoot(String aName, String... aDependencies) throws Exception
    {
        var root = tempDir.resolve(aName);
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/DEPENDENCIES.json"),
                "{ \"dependencies\": [" + String.join(",", aDependencies) + "] }");
        return root;
    }

    private static String dependency(String aArtifactId, String aVersion)
    {
        return "{ \"name\": \"" + aArtifactId + "\", \"groupId\": \"org.example\", "
                + "\"artifactId\": \"" + aArtifactId + "\", \"version\": \"" + aVersion + "\", "
                + "\"licenses\": [ { \"name\": \"MIT\" } ] }";
    }

    private static URLClassLoader classLoader(Path... aRoots) throws Exception
    {
        var urls = new URL[aRoots.length];
        for (int i = 0; i < aRoots.length; i++) {
            urls[i] = aRoots[i].toUri().toURL();
        }
        // No parent, so that the module's own resources do not interfere
        return new URLClassLoader(urls, null);
    }
}
