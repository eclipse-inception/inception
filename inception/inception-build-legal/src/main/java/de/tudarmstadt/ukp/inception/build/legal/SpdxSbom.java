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

import static de.tudarmstadt.ukp.inception.build.legal.LegalDocumentationGenerator.embeddedLicense;
import static de.tudarmstadt.ukp.inception.build.legal.LegalDocumentationGenerator.licenseExpression;
import static de.tudarmstadt.ukp.inception.support.json.JSONUtil.getObjectMapper;
import static java.lang.String.join;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZoneOffset.UTC;
import static java.time.format.DateTimeFormatter.ofPattern;
import static java.util.regex.Pattern.compile;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

import de.tudarmstadt.ukp.inception.build.legal.LegalDocumentationGenerator.Component;
import de.tudarmstadt.ukp.inception.support.about.SpdxPackage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the SBOM of a distribution as an SPDX 2.3 JSON document: one package for the distribution
 * itself, which contains one package per third-party component. There is no dependency graph; the
 * Eclipse handbook does not ask for one.
 */
class SpdxSbom
{
    /** The SPDX 2.3 JSON schema, from https://github.com/spdx/spdx-spec (v2.3, CC-BY-3.0). */
    static final String SCHEMA_RESOURCE = "spdx-schema-2.3.json";

    /** Version of the SPDX license list that the license texts are taken from. */
    static final String LICENSE_LIST_VERSION = "3.29";

    private static final String DOCUMENT_ID = "SPDXRef-DOCUMENT";
    private static final String DISTRIBUTION_ID = "SPDXRef-Distribution";
    private static final String NOASSERTION = "NOASSERTION";
    private static final String LICENSE_REF = "LicenseRef-";

    /** SPDX agent syntax for originators and suppliers. */
    private static final Pattern ENTITY = compile("(Person|Organization): .+|NOASSERTION");
    private static final Pattern NON_ID_CHARACTERS = compile("[^A-Za-z0-9.-]+");
    private static final Pattern DIGITS = compile("\\d+");
    private static final Pattern ISO_DATE_TIME = compile("\\d{4}-\\d{2}-\\d{2}T.*");
    private static final DateTimeFormatter CREATED = ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(UTC);

    record Project(String name, String version, String license, String url, String fileName,
            Instant created)
    {}

    private SpdxSbom()
    {
        // Static helpers only
    }

    static ObjectNode build(Project aProject, List<Component> aComponents,
            List<SpdxPackage> aEmbedded, Map<String, byte[]> aLicenseTexts)
    {
        var mapper = getObjectMapper();
        var document = mapper.createObjectNode();
        document.put("spdxVersion", "SPDX-2.3");
        document.put("dataLicense", "CC0-1.0");
        document.put("SPDXID", DOCUMENT_ID);
        document.put("name", aProject.fileName());
        document.put("documentNamespace", aProject.url() + "/spdx/" + aProject.fileName());

        var creationInfo = document.putObject("creationInfo");
        creationInfo.put("created", CREATED.format(aProject.created()));
        creationInfo.putArray("creators").add("Tool: inception-build-legal-" + aProject.version());
        creationInfo.put("licenseListVersion", LICENSE_LIST_VERSION);

        // Keyed and so sorted by SPDX id, so the document is reproducible
        var packages = new TreeMap<String, ObjectNode>();
        for (var component : aComponents) {
            var node = componentPackage(component);
            packages.put(uniqueId(packages, node.get("SPDXID").asString()), node);
        }
        for (var embedded : aEmbedded) {
            var node = embeddedPackage(embedded);
            var id = uniqueId(packages, node.get("SPDXID").asString());
            node.put("SPDXID", id);
            packages.put(id, node);
        }

        var packageArray = document.putArray("packages");
        packageArray.add(distributionPackage(aProject));
        packages.values().forEach(packageArray::add);

        var relationships = document.putArray("relationships");
        relationships.add(relationship(DOCUMENT_ID, "DESCRIBES", DISTRIBUTION_ID));
        for (var id : packages.keySet()) {
            relationships.add(relationship(DISTRIBUTION_ID, "CONTAINS", id));
        }

        // A license reference used in the document has to be defined in it
        var licenseRefs = aLicenseTexts.entrySet().stream() //
                .filter(e -> e.getKey().startsWith(LICENSE_REF)) //
                .toList();
        if (!licenseRefs.isEmpty()) {
            var infos = document.putArray("hasExtractedLicensingInfos");
            for (var licenseRef : licenseRefs) {
                var text = new String(licenseRef.getValue(), UTF_8);
                var info = infos.addObject();
                info.put("licenseId", licenseRef.getKey());
                info.put("name", text.lines().findFirst().orElse(licenseRef.getKey()).strip());
                info.put("extractedText", text);
            }
        }

        return document;
    }

    private static ObjectNode distributionPackage(Project aProject)
    {
        var node = getObjectMapper().createObjectNode();
        node.put("SPDXID", DISTRIBUTION_ID);
        node.put("name", aProject.name());
        node.put("versionInfo", aProject.version());
        node.put("packageFileName", aProject.fileName());
        node.put("downloadLocation", NOASSERTION);
        node.put("homepage", aProject.url());
        node.put("filesAnalyzed", false);
        node.put("licenseConcluded", aProject.license());
        node.put("licenseDeclared", aProject.license());
        node.put("primaryPackagePurpose", "APPLICATION");
        return node;
    }

    private static ObjectNode componentPackage(Component aComponent)
    {
        var maven = "maven".equals(aComponent.kind());
        var runtime = "runtime".equals(aComponent.kind());
        var name = maven ? aComponent.groupId() + ":" + aComponent.name() : aComponent.name();

        var node = getObjectMapper().createObjectNode();
        node.put("SPDXID",
                id(aComponent.kind() + "-" + name.replace(':', '-') + "-" + aComponent.version()
                        + (aComponent.classifier() != null ? "-" + aComponent.classifier() : "")));
        node.put("name", name);
        node.put("versionInfo", aComponent.version());
        if (runtime && aComponent.groupId() != null) {
            node.put("supplier", "Organization: " + aComponent.groupId());
        }
        if (aComponent.fileName() != null) {
            node.put("packageFileName", aComponent.fileName());
        }
        node.put("downloadLocation", NOASSERTION);
        node.put("filesAnalyzed", false);
        if (aComponent.sha256() != null) {
            var checksum = node.putArray("checksums").addObject();
            checksum.put("algorithm", "SHA256");
            checksum.put("checksumValue", aComponent.sha256());
        }
        if (runtime) {
            // The runtime's own license; its folder legal lists further third-party content under
            // other licenses, which the runtime includes
            node.put("licenseConcluded", NOASSERTION);
            node.put("licenseDeclared", aComponent.license());
            node.put("licenseComments", "The runtime is licensed under " + aComponent.license()
                    + ". It includes third-party content under further licenses, listed in its "
                    + "folder " + aComponent.fileName() + "/legal.");
        }
        else {
            node.put("licenseConcluded", aComponent.license());
            // A concluded license replaces a declaration that does not map to SPDX
            node.put("licenseDeclared", "concluded".equals(aComponent.authority()) ? NOASSERTION
                    : aComponent.license());
        }
        var purl = node.putArray("externalRefs").addObject();
        purl.put("referenceCategory", "PACKAGE-MANAGER");
        purl.put("referenceType", "purl");
        purl.put("referenceLocator", aComponent.purl());
        if (!runtime) {
            node.put("primaryPackagePurpose", "LIBRARY");
        }
        return node;
    }

    /**
     * Converts an entry of our {@code DEPENDENCIES-EMBEDDED.spdx.json} fragments. They list the
     * embedded files by path in {@code hasFiles}, where SPDX expects the ids of file elements, so
     * the paths go into the package comment instead.
     */
    private static ObjectNode embeddedPackage(SpdxPackage aPackage)
    {
        var node = getObjectMapper().createObjectNode();
        node.put("SPDXID", id("embedded-" + aPackage.getName()
                + (aPackage.getVersionInfo() != null ? "-" + aPackage.getVersionInfo() : "")));
        node.put("name", aPackage.getName());
        putIfNotNull(node, "versionInfo", aPackage.getVersionInfo());
        putIfNotNull(node, "supplier", aPackage.getSupplier());
        putIfNotNull(node, "originator", aPackage.getOriginator());
        node.put("downloadLocation",
                aPackage.getDownloadLocation() != null ? aPackage.getDownloadLocation()
                        : NOASSERTION);
        putIfNotNull(node, "homepage", aPackage.getHomepage());
        node.put("filesAnalyzed", false);
        node.put("licenseConcluded",
                licenseExpression(embeddedLicense(aPackage)).orElse(NOASSERTION));
        node.put("licenseDeclared",
                aPackage.getLicenseDeclared() != null
                        ? licenseExpression(aPackage.getLicenseDeclared()).orElse(NOASSERTION)
                        : NOASSERTION);
        putIfNotNull(node, "licenseComments", aPackage.getLicenseComments());
        putIfNotNull(node, "description", aPackage.getDescription());
        if (aPackage.getHasFiles() != null && !aPackage.getHasFiles().isEmpty()) {
            node.put("comment", "Embedded in: " + join(", ", aPackage.getHasFiles()));
        }
        return node;
    }

    private static ObjectNode relationship(String aElement, String aType, String aRelatedElement)
    {
        var node = getObjectMapper().createObjectNode();
        node.put("spdxElementId", aElement);
        node.put("relationshipType", aType);
        node.put("relatedSpdxElement", aRelatedElement);
        return node;
    }

    private static void putIfNotNull(ObjectNode aNode, String aField, String aValue)
    {
        if (aValue != null) {
            aNode.put(aField, aValue);
        }
    }

    private static String id(String aName)
    {
        return "SPDXRef-" + NON_ID_CHARACTERS.matcher(aName).replaceAll("-");
    }

    private static String uniqueId(Map<String, ?> aTaken, String aId)
    {
        var id = aId;
        for (var i = 2; aTaken.containsKey(id); i++) {
            id = aId + "-" + i;
        }
        return id;
    }

    /**
     * @return whether a value follows the SPDX syntax for originators and suppliers, e.g.
     *         {@code Organization: Example Inc.}
     */
    static boolean isEntity(String aValue)
    {
        return ENTITY.matcher(aValue).matches();
    }

    /**
     * Determines the creation time the same way the DKPro parent POM determines the copyright year
     * of the NOTICE file: from a pinned {@code project.build.outputTimestamp}, given as ISO 8601 or
     * as epoch seconds, so that a reproducible build stays reproducible. Without a pin, or with the
     * 1980 placeholder Maven uses when the property is not set, it is the current time.
     */
    static Instant creationTime(String aOutputTimestamp, Instant aNow)
    {
        if (aOutputTimestamp != null) {
            if (DIGITS.matcher(aOutputTimestamp).matches()) {
                return Instant.ofEpochSecond(Long.parseLong(aOutputTimestamp));
            }
            if (ISO_DATE_TIME.matcher(aOutputTimestamp).matches()
                    && !aOutputTimestamp.startsWith("1980-")) {
                return OffsetDateTime.parse(aOutputTimestamp).toInstant();
            }
        }
        return aNow;
    }

    /**
     * @return the violations of the SPDX 2.3 JSON schema, if any.
     */
    static List<String> validate(JsonNode aDocument) throws IOException
    {
        try (var is = SpdxSbom.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (is == null) {
                throw new IOException("Resource not found: " + SCHEMA_RESOURCE);
            }
            var schema = SchemaRegistry.withDialect(Dialects.getDraft7()).getSchema(is);
            return schema.validate(aDocument).stream() //
                    .map(Object::toString) //
                    .toList();
        }
    }
}
