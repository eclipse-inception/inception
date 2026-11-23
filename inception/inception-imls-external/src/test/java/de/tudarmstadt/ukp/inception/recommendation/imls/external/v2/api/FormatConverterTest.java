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
package de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.api;

import static de.tudarmstadt.ukp.inception.recommendation.imls.external.util.Fixtures.loadSmallCas;
import static de.tudarmstadt.ukp.inception.recommendation.imls.external.util.Fixtures.loadSmallDocument;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.Feature;
import org.apache.uima.cas.Type;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.fit.util.CasUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FormatConverterTest
{

    private final String NER = "de.tudarmstadt.ukp.dkpro.core.api.ner.type.NamedEntity";
    private final String VALUE = "value";

    private FormatConverter sut;

    @BeforeEach
    public void setUp() throws Exception
    {
        sut = new FormatConverter();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        sut = null;
    }

    @Test
    public void testDocumentFromCas() throws Exception
    {
        CAS cas = loadSmallCas();
        Document expected = loadSmallDocument();

        Document document = sut.documentFromCas(cas,
                "de.tudarmstadt.ukp.dkpro.core.api.ner.type.NamedEntity", "value", 23);

        assertThat(document).isEqualTo(expected);
    }

    @Test
    public void testLoadDocumentIntoCas() throws Exception
    {
        CAS cas = JCasFactory.createJCas().getCas();
        Document document = loadSmallDocument();
        sut.loadIntoCas(document, NER, VALUE, cas);

        Type targetType = CasUtil.getAnnotationType(cas, NER);
        Feature feature = targetType.getFeatureByBaseName(VALUE);

        List<AnnotationFS> result = new ArrayList<>(CasUtil.select(cas, targetType));

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getFeatureValueAsString(feature)).isEqualTo("PER");
        assertThat(result.get(1).getFeatureValueAsString(feature)).isEqualTo("OTH");
        assertThat(result.get(2).getFeatureValueAsString(feature)).isEqualTo("OTH");
    }
}
