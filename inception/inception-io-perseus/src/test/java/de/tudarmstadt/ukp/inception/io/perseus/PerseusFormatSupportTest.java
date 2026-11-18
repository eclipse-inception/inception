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
package de.tudarmstadt.ukp.inception.io.perseus;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.uima.fit.factory.CasFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;

class PerseusFormatSupportTest
{
    private static final String BODY = """
            <treebank version="1.5" xml:lang="grc" format="aldt">
            <sentence id="1" document_id="d" subdoc="s">
            <word id="1" form="A%sB" lemma="x" postag="n-s---mn-" relation="SBJ" head="0"/>
            </sentence>
            </treebank>
            """;

    private @TempDir Path tempDir;

    @Test
    void thatPlainFileIsRead() throws Exception
    {
        var file = tempDir.resolve("doc.xml");
        Files.writeString(file, BODY.formatted("-hello-"), UTF_8);

        var cas = CasFactory.createCas();
        new PerseusFormatSupport().read(new SourceDocument(), cas, file.toFile());

        assertThat(cas.getDocumentText()).contains("A-hello-B");
    }

    @Test
    void thatDoctypeIsRejected() throws Exception
    {
        var dtdFile = tempDir.resolve("doc.dtd");
        Files.writeString(dtdFile, "<!ENTITY ent \"value\">", UTF_8);

        var file = tempDir.resolve("doc.xml");
        Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE treebank [ <!ENTITY % p SYSTEM "%s"> %p; ]>
                """.replace("%s", dtdFile.toUri().toString()) + BODY.formatted("&ent;"), UTF_8);

        var cas = CasFactory.createCas();
        assertThatThrownBy(
                () -> new PerseusFormatSupport().read(new SourceDocument(), cas, file.toFile())) //
                        .isInstanceOf(IOException.class) //
                        .hasMessageContaining("DOCTYPE");
        assertThat(cas.getDocumentText()).isNull();
    }
}
