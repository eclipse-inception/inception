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

import static de.tudarmstadt.ukp.inception.support.xml.XmlParserUtils.newSaxParser;
import static org.apache.uima.fit.factory.CollectionReaderFactory.createReaderDescription;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.uima.cas.CAS;
import org.apache.uima.collection.CollectionException;
import org.apache.uima.collection.CollectionReaderDescription;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.dkpro.core.api.resources.CompressionUtils;
import org.dkpro.core.io.perseus.PerseusReader;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import de.tudarmstadt.ukp.clarin.webanno.api.format.UimaReaderWriterFormatSupport_ImplBase;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.inception.io.perseus.config.PerseusFormatAutoConfiguration;

/**
 * <p>
 * This class is exposed as a Spring Component via
 * {@link PerseusFormatAutoConfiguration#perseusFormatSupport()}.
 * </p>
 */
public class PerseusFormatSupport
    extends UimaReaderWriterFormatSupport_ImplBase
{
    public static final String ID = "perseus_2.1";
    public static final String NAME = "Perseus treebank XML (2.1)";

    @Override
    public String getId()
    {
        return ID;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public boolean isReadable()
    {
        return true;
    }

    @Override
    public boolean isWritable()
    {
        return false;
    }

    @Override
    public CollectionReaderDescription getReaderDescription(Project aProject,
            TypeSystemDescription aTSD)
        throws ResourceInitializationException
    {
        return createReaderDescription(PerseusReader.class, aTSD);
    }

    @Override
    public void read(SourceDocument aDocument, CAS aCas, File aFile)
        throws ResourceInitializationException, IOException, CollectionException
    {
        validateXml(aFile);

        super.read(aDocument, aCas, aFile);
    }

    static void validateXml(File aFile) throws IOException
    {
        try (var is = CompressionUtils.getInputStream(aFile.getName(),
                Files.newInputStream(aFile.toPath()))) {
            newSaxParser().parse(is, new DefaultHandler());
        }
        catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Unable to parse [" + aFile.getName()
                    + "] - note that DOCTYPE declarations are not supported", e);
        }
    }
}
