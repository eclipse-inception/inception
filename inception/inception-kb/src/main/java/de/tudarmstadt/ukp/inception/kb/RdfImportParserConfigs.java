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

import org.eclipse.rdf4j.rio.ParserConfig;
import org.eclipse.rdf4j.rio.helpers.XMLParserSettings;

public class RdfImportParserConfigs
{
    private RdfImportParserConfigs()
    {
        // No instances
    }

    /**
     * Parser configuration for reading RDF into a knowledge base.
     * <p>
     * RDF4J 5.3.2 hardened its XML parsers against XXE (follow-up to CVE-2018-1000644) and as part
     * of that started rejecting {@code DOCTYPE} declarations outright. That is too strict for us:
     * many OWL vocabularies - e.g. OLiA, which the slow profile tests import - declare
     * <b>internal</b> entities in a {@code DOCTYPE} purely to abbreviate namespace IRIs, and those
     * files must remain importable. The test fixture {@code tagset_custom_label.rdf} covers this in
     * the default build.
     * <p>
     * So we accept the {@code DOCTYPE} declaration again, but only that. Every setting that would
     * make the parser reach outside the document stays off, and it is the outside reach - not the
     * declaration - that constitutes the XXE risk: no external general entities, no external
     * parameter entities, no external DTD loading, and XML secure processing stays enabled.
     * <p>
     * Apply this per import operation. Do not turn {@code DISALLOW_DOCTYPE_DECL} off globally.
     *
     * @return a parser configuration tolerating internal {@code DOCTYPE} entity declarations while
     *         keeping all external entity resolution disabled.
     */
    public static ParserConfig doctypeTolerantParserConfig()
    {
        var config = new ParserConfig();
        config.set(XMLParserSettings.DISALLOW_DOCTYPE_DECL, false);
        config.set(XMLParserSettings.SECURE_PROCESSING, true);
        config.set(XMLParserSettings.LOAD_EXTERNAL_DTD, false);
        config.set(XMLParserSettings.EXTERNAL_GENERAL_ENTITIES, false);
        config.set(XMLParserSettings.EXTERNAL_PARAMETER_ENTITIES, false);
        return config;
    }
}
