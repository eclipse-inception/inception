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
package de.tudarmstadt.ukp.inception.io.tei.dkprobackport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.uima.fit.factory.CollectionReaderFactory.createReader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.uima.fit.factory.JCasFactory;
import org.dkpro.core.api.io.ResourceCollectionReaderBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeiReaderTest
{
    private static final String ENTITY_CONTENT = "ENTITY-CONTENT";

    private static final String BODY = """
            <TEI xmlns="http://www.tei-c.org/ns/1.0">
            <teiHeader><fileDesc><titleStmt><title>t</title></titleStmt>
            <publicationStmt><p>p</p></publicationStmt><sourceDesc><p>s</p></sourceDesc>
            </fileDesc></teiHeader>
            <text><body><p>MARKER_A %s MARKER_B</p></body></text>
            </TEI>
            """;

    private @TempDir Path tempDir;

    @Test
    void thatPlainTeiIsRead() throws Exception
    {
        var file = tempDir.resolve("doc.xml");
        Files.writeString(file, BODY.formatted("hello"), UTF_8);

        var text = read(file);

        assertThat(text).contains("MARKER_A hello MARKER_B");
    }

    @Test
    void thatDoctypeIsRejected() throws Exception
    {
        var entityFile = tempDir.resolve("entity.txt");
        Files.writeString(entityFile, ENTITY_CONTENT, UTF_8);

        var file = tempDir.resolve("doc.xml");
        Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE TEI [ <!ENTITY ent SYSTEM "%s"> ]>
                """.formatted(entityFile.toUri()) + BODY.formatted("&ent;"), UTF_8);

        assertThatThrownBy(() -> read(file)) //
                .satisfies(e -> assertThat(e).hasStackTraceContaining("DOCTYPE")) //
                .satisfies(e -> assertThat(e).hasNoSuppressedExceptions()) //
                .rootCause().message().doesNotContain(ENTITY_CONTENT);
    }

    private String read(Path aFile) throws Exception
    {
        var reader = createReader(TeiReader.class, //
                ResourceCollectionReaderBase.PARAM_SOURCE_LOCATION, aFile.getParent().toString(), //
                ResourceCollectionReaderBase.PARAM_PATTERNS, "[+]" + aFile.getFileName());
        try {
            var jcas = JCasFactory.createJCas();
            reader.hasNext();
            reader.getNext(jcas.getCas());
            return jcas.getDocumentText();
        }
        finally {
            reader.destroy();
        }
    }
}
