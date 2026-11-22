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

import static de.tudarmstadt.ukp.inception.build.legal.LegalDocumentationGenerator.licenseExpression;
import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.loadEmbeddedSpdxDependencies;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import de.tudarmstadt.ukp.inception.support.about.SpdxExternalRef;
import de.tudarmstadt.ukp.inception.support.about.SpdxPackage;

/**
 * Third-party code shaded or copied into a third-party library, which the library's own metadata
 * does not list, as declared in {@value #RESOURCE}: an SPDX package in the format of our
 * {@code DEPENDENCIES-EMBEDDED.spdx.json} fragments, plus the library it is contained in.
 *
 * @param containerPurl
 *            the purl of the containing library, with the version the entry was checked for
 */
record ShadedContent(SpdxPackage spdxPackage, String containerPurl) {
    /** Resource in the package of this class. */
    static final String RESOURCE = "DEPENDENCIES-SHADED.spdx.json";

    static final String CONTAINED_IN = "contained-in";

    static List<ShadedContent> load() throws IOException
    {
        try (var is = ShadedContent.class.getResourceAsStream(RESOURCE)) {
            if (is == null) {
                throw new IOException("Resource not found: " + RESOURCE);
            }
            return load(is);
        }
    }

    static List<ShadedContent> load(InputStream aIs) throws IOException
    {
        var result = new ArrayList<ShadedContent>();
        for (var pkg : loadEmbeddedSpdxDependencies(aIs).getPackages()) {
            var where = "[" + pkg.getName() + "] in " + RESOURCE;
            var container = ref(pkg, CONTAINED_IN)
                    .orElseThrow(() -> new IOException(where + " names no containing library"));
            if (!container.contains("@")) {
                throw new IOException(where + ": the containing library needs the version the "
                        + "entry was checked for: " + container);
            }
            if (pkg.getOriginator() != null && !SpdxSbom.isEntity(pkg.getOriginator())) {
                throw new IOException(where + " names its originator without \"Person: \" or "
                        + "\"Organization: \": " + pkg.getOriginator());
            }
            if (licenseExpression(pkg.getLicenseConcluded()).isEmpty()) {
                throw new IOException(where + " has no known SPDX license expression: "
                        + pkg.getLicenseConcluded());
            }
            result.add(new ShadedContent(pkg, container));
        }
        return result;
    }

    private static Optional<String> ref(SpdxPackage aPackage, String aType)
    {
        return Optional.ofNullable(aPackage.getExternalRefs()).orElse(List.of()).stream() //
                .filter(r -> aType.equals(r.getReferenceType())) //
                .map(SpdxExternalRef::getReferenceLocator) //
                .findFirst();
    }

    /**
     * @return the purl of the containing library without version, e.g.
     *         {@code pkg:maven/org.aspectj/aspectjweaver}.
     */
    String containerKey()
    {
        return containerPurl.substring(0, containerPurl.lastIndexOf('@'));
    }

    String containerVersion()
    {
        return containerPurl.substring(containerPurl.lastIndexOf('@') + 1);
    }

    /**
     * @return whether this entry covers a folder of the library with signs of bundled code, by one
     *         of the folders in its {@code hasFiles}, e.g. {@code com/nimbusds/jose/shaded/gson/}.
     */
    boolean coversPath(String aFolder)
    {
        return Optional.ofNullable(spdxPackage.getHasFiles()).orElse(List.of()).stream() //
                .anyMatch(f -> aFolder.startsWith(f) || f.startsWith(aFolder));
    }

    /**
     * @return whether the bundled code has Maven coordinates in the given group, so that the
     *         embedded POM of that group is explained.
     */
    boolean hasGroupId(String aGroupId)
    {
        return ref(spdxPackage, SpdxExternalRef.TYPE_PURL) //
                .filter(p -> p.startsWith("pkg:maven/")) //
                .map(p -> p.substring("pkg:maven/".length()).split("/")[0]) //
                .filter(aGroupId::equals) //
                .isPresent();
    }

    String license()
    {
        return licenseExpression(spdxPackage.getLicenseConcluded()).orElseThrow();
    }

    /**
     * @return the purl of the shaded package with its version, if both are known.
     */
    Optional<String> purl()
    {
        var version = spdxPackage.getVersionInfo();
        return ref(spdxPackage, SpdxExternalRef.TYPE_PURL) //
                .filter(p -> version != null) //
                .map(p -> p + "@" + version);
    }
}
