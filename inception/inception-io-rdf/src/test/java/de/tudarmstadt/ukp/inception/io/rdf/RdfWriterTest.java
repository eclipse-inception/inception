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
package de.tudarmstadt.ukp.inception.io.rdf;

import static java.lang.String.join;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.sort;
import static org.apache.uima.fit.factory.AnalysisEngineFactory.createEngine;
import static org.apache.uima.fit.factory.CollectionReaderFactory.createReader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.contentOf;
import static org.assertj.core.api.Assertions.tuple;
import static org.dkpro.core.testing.IOTestRunner.testOneWay;
import static org.dkpro.core.testing.IOTestRunner.testRoundTrip;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;

import org.apache.uima.fit.factory.JCasFactory;
import org.dkpro.core.io.conll.Conll2006Reader;
import org.dkpro.core.io.conll.Conll2006Writer;
import org.dkpro.core.testing.TestOptions;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.tudarmstadt.ukp.dkpro.core.api.metadata.type.DocumentMetaData;
import de.tudarmstadt.ukp.dkpro.core.api.ner.type.NamedEntity;

public class RdfWriterTest
{
    @Test
    void oneWay() throws Exception
    {
        testOneWay(Conll2006Reader.class, // the reader
                RdfWriter.class, // the writer
                "conll/2006/de-ref.ttl", // the reference file for the output
                "conll/2006/de-orig.conll", // the input file for the test
                new TestOptions().resultAssertor(this::assertModelEquals));
    }

    @Test
    void otherWay() throws Exception
    {
        testOneWay(RdfReader.class, // the reader
                Conll2006Writer.class, // the writer
                "ttl/de-ref.conll", // the reference file for the output
                "ttl/de-orig.ttl"); // the input file for the test
    }

    @Test
    void readWriteWithIriFeatures(@TempDir File aTemp) throws Exception
    {
        var cas = JCasFactory.createJCas();
        cas.setDocumentText("John");

        var dmd = DocumentMetaData.create(cas);
        dmd.setDocumentId("test.txt");

        var ne = new NamedEntity(cas, 0, 4);
        ne.setValue("PER");
        ne.setIdentifier("iri:somewhere");
        ne.addToIndexes();

        var writer = createEngine( //
                RdfWriter.class, //
                RdfWriter.PARAM_IRI_FEATURES,
                NamedEntity._TypeName + ":" + NamedEntity._FeatName_identifier,
                RdfWriter.PARAM_STRIP_EXTENSION, true, //
                RdfWriter.PARAM_TARGET_LOCATION, aTemp);

        writer.process(cas);

        var targetFile = new File(aTemp, "test.ttl");
        assertThat(contentOf(targetFile, UTF_8)) //
                .contains("\"PER\"").contains("<iri:somewhere>");

        cas.reset();

        var reader = createReader( //
                RdfReader.class, RdfReader.PARAM_SOURCE_LOCATION, targetFile);

        reader.getNext(cas.getCas());

        assertThat(cas.select(NamedEntity.class).asList()) //
                .extracting(NamedEntity::getIdentifier, NamedEntity::getValue) //
                .containsExactly(tuple("iri:somewhere", "PER"));
    }

    @Test
    void roundTrip() throws Exception
    {
        // Uses the output of the current writer, so the ids are stable on the round trip. The
        // file name must match the document ID in the data, because the writer names its output
        // after it.
        testRoundTrip(RdfReader.class, // the reader
                RdfWriter.class, // the writer
                "roundtrip/de-orig.ttl", //
                new TestOptions().resultAssertor(this::assertModelEquals));
    }

    private void assertModelEquals(File expected, File actual)
    {
        try {
            var sExpected = new ArrayList<String>();
            try (var is = new FileInputStream(expected)) {
                Rio.parse(is, RDFFormat.TURTLE).forEach(s -> sExpected.add(s.toString()));
                ;
            }
            sort(sExpected);

            var sActual = new ArrayList<String>();
            try (var is = new FileInputStream(actual)) {
                Rio.parse(is, RDFFormat.TURTLE).forEach(s -> sActual.add(s.toString()));
                ;
            }
            sort(sActual);

            assertThat(join("\n", sActual)).isEqualTo(join("\n", sExpected));
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
