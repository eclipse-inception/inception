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
package de.tudarmstadt.ukp.inception.build.legal;

import static de.tudarmstadt.ukp.inception.build.legal.LegalDocumentationGenerator.JAVA_RUNTIME_LICENSE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.newBufferedReader;
import static java.nio.file.Files.readString;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

import de.tudarmstadt.ukp.inception.build.legal.LegalDocumentationGenerator.Component;

/**
 * A Java runtime bundled with a distribution, as described by its {@code release} file.
 *
 * @param path
 *            the folder of the runtime, relative to the artifact
 * @param gplWithClasspathException
 *            whether {@code legal/java.base/LICENSE} is the GPL v2 with the Classpath exception, as
 *            for OpenJDK builds
 */
record JavaRuntime(String path, String implementor, String implementorVersion, String version,
        boolean gplWithClasspathException)
{
    static JavaRuntime read(Path aHome, String aPath) throws IOException
    {
        var release = new Properties();
        try (var reader = newBufferedReader(aHome.resolve("release"), UTF_8)) {
            release.load(reader);
        }

        var license = aHome.resolve("legal/java.base/LICENSE");
        var licenseText = isRegularFile(license) ? readString(license, UTF_8) : "";
        var gplWithClasspathException = licenseText.contains("GNU General Public License")
                && licenseText.contains("Version 2, June 1991")
                && licenseText.contains("\"CLASSPATH\" EXCEPTION");

        var version = unquote(release.getProperty("JAVA_VERSION"));
        if (version == null || version.isBlank()) {
            throw new IOException(
                    "The release file of the Java runtime in " + aHome + " has no JAVA_VERSION");
        }

        return new JavaRuntime(aPath, unquote(release.getProperty("IMPLEMENTOR")),
                unquote(release.getProperty("IMPLEMENTOR_VERSION")), version,
                gplWithClasspathException);
    }

    private static String unquote(String aValue)
    {
        if (aValue != null && aValue.length() >= 2 && aValue.startsWith("\"")
                && aValue.endsWith("\"")) {
            return aValue.substring(1, aValue.length() - 1);
        }
        return aValue;
    }

    /**
     * @return the name to show, e.g. {@code Temurin-25.0.5+8} by {@code Eclipse Adoptium}.
     */
    String displayName()
    {
        return implementorVersion != null ? implementorVersion : "OpenJDK " + version;
    }

    Component toComponent()
    {
        return Component.runtime(implementor, version, JAVA_RUNTIME_LICENSE, path);
    }
}
