/*
 * Copyright 2017
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.io.tei.dkprobackport;

import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.ATTR_FUNCTION;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.ATTR_LEMMA;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.ATTR_POS;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.ATTR_TYPE;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.ATTR_XML_ID;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.TAG_CHARACTER;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.TAG_MULTIWORD;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.TAG_PARAGRAPH;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.TAG_PHRASE;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.TAG_RS;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.TAG_SUNIT;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.TAG_TEI_DOC;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.TAG_TEXT;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.TAG_TITLE;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.TAG_U;
import static de.tudarmstadt.ukp.inception.io.tei.dkprobackport.TeiConstants.TAG_WORD;
import static de.tudarmstadt.ukp.inception.support.xml.XmlParserUtils.enableNamespaceSupport;
import static de.tudarmstadt.ukp.inception.support.xml.XmlParserUtils.newSaxParser;
import static de.tudarmstadt.ukp.inception.support.xml.XmlParserUtils.newSaxParserFactory;
import static java.util.Arrays.asList;
import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.trim;
import static org.dkpro.core.api.resources.MappingProviderFactory.createPosMappingProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.CASException;
import org.apache.uima.cas.Type;
import org.apache.uima.collection.CollectionException;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.descriptor.MimeTypeCapability;
import org.apache.uima.fit.descriptor.ResourceMetaData;
import org.apache.uima.fit.descriptor.TypeCapability;
import org.apache.uima.fit.util.FSCollectionFactory;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.Logger;
import org.dkpro.core.api.io.ResourceCollectionReaderBase;
import org.dkpro.core.api.lexmorph.pos.POSUtils;
import org.dkpro.core.api.parameter.ComponentParameters;
import org.dkpro.core.api.parameter.MimeTypes;
import org.dkpro.core.api.resources.MappingProvider;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.dom4j.io.SAXWriter;
import org.jaxen.JaxenException;
import org.jaxen.XPath;
import org.jaxen.dom4j.Dom4jXPath;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.pos.POS;
import de.tudarmstadt.ukp.dkpro.core.api.metadata.type.DocumentMetaData;
import de.tudarmstadt.ukp.dkpro.core.api.ner.type.NamedEntity;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Lemma;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Paragraph;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import de.tudarmstadt.ukp.dkpro.core.api.syntax.type.constituent.Constituent;
import de.tudarmstadt.ukp.dkpro.core.api.syntax.type.constituent.ROOT;
import de.tudarmstadt.ukp.inception.support.text.TrimUtils;
import eu.openminted.share.annotations.api.DocumentationResource;

/**
 * Reader for the TEI XML.
 * <table border="1">
 * <caption>Supported TEI XML elements and attributes</caption>
 * <tr>
 * <th>Element</th>
 * <th>Description</th>
 * <th>DKPro Core type</th>
 * <th>Attribute mappings</th>
 * </tr>
 * <tr>
 * <td><code>TEI</code></td>
 * <td>document boundary</td>
 * <td><code>getNext(...)</code> returns one TEI document at a time</td>
 * <td></td>
 * </tr>
 * <tr>
 * <td><code>title</code></td>
 * <td>document title</td>
 * <td>DocumentMetaData</td>
 * <td></td>
 * </tr>
 * <tr>
 * <td><code>s</code></td>
 * <td>s-unit</td>
 * <td>Sentence</td>
 * <td></td>
 * </tr>
 * <tr>
 * <td><code>u</code></td>
 * <td>utterance</td>
 * <td>Sentence</td>
 * <td></td>
 * <tr>
 * <td><code>p</code></td>
 * <td>paragraph</td>
 * <td>Paragraph</td>
 * <td></td>
 * </tr>
 * <tr>
 * <td><code>rs</code></td>
 * <td>referencing string</td>
 * <td>NamedEntity</td>
 * <td><code>type</code> -&gt; value</td>
 * </tr>
 * <tr>
 * <td><code>phr</code></td>
 * <td>phrase</td>
 * <td>Constituent</td>
 * <td><code>type</code> -&gt; constituentType, <code>function</code> -&gt; syntacticFunction</td>
 * </tr>
 * <tr>
 * <td><code>w</code></td>
 * <td>word</td>
 * <td>Token</td>
 * <td>(<code>pos</code>, <code>type</code>) -&gt; POS.PosValue (<code>pos</code> preferred over
 * <code>type</code>)</td>
 * </tr>
 * <tr>
 * <td><code>mw</code></td>
 * <td>multi-word</td>
 * <td>Token</td>
 * <td>same as for <code>w</code></td>
 * </tr>
 * <tr>
 * <td><code>c</code></td>
 * <td>character</td>
 * <td>Token</td>
 * <td>same as for <code>w</code></td>
 * </tr>
 * </table>
 */
@ResourceMetaData(name = "TEI XML Reader")
@DocumentationResource("${docbase}/format-reference.html#format-${command}")
@MimeTypeCapability({ MimeTypes.APPLICATION_TEI_XML })
@TypeCapability(outputs = { "de.tudarmstadt.ukp.dkpro.core.api.metadata.type.DocumentMetaData",
        "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Paragraph",
        "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence",
        "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token",
        "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Lemma",
        "de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.pos.POS",
        "de.tudarmstadt.ukp.dkpro.core.api.syntax.type.constituent.Constituent",
        "de.tudarmstadt.ukp.dkpro.core.api.ner.type.NamedEntity" })
public class TeiReader
    extends ResourceCollectionReaderBase
{
    /**
     * Write token annotations to the CAS.
     */
    public static final String PARAM_READ_TOKEN = ComponentParameters.PARAM_READ_TOKEN;
    @ConfigurationParameter(name = PARAM_READ_TOKEN, mandatory = true, defaultValue = "true")
    private boolean readToken;

    /**
     * Write part-of-speech annotations to the CAS.
     */
    public static final String PARAM_READ_POS = ComponentParameters.PARAM_READ_POS;
    @ConfigurationParameter(name = PARAM_READ_POS, mandatory = true, defaultValue = "true")
    private boolean readPOS;

    /**
     * Write lemma annotations to the CAS.
     */
    public static final String PARAM_READ_LEMMA = ComponentParameters.PARAM_READ_LEMMA;
    @ConfigurationParameter(name = PARAM_READ_LEMMA, mandatory = true, defaultValue = "true")
    private boolean readLemma;

    /**
     * Write sentence annotations to the CAS.
     */
    public static final String PARAM_READ_SENTENCE = ComponentParameters.PARAM_READ_SENTENCE;
    @ConfigurationParameter(name = PARAM_READ_SENTENCE, mandatory = true, defaultValue = "true")
    private boolean readSentence;

    /**
     * Write constituent annotations to the CAS.
     */
    public static final String PARAM_READ_CONSTITUENT = ComponentParameters.PARAM_READ_CONSTITUENT;
    @ConfigurationParameter(name = PARAM_READ_CONSTITUENT, mandatory = true, defaultValue = "true")
    private boolean readConstituent;

    /**
     * Write named entity annotations to the CAS.
     */
    public static final String PARAM_READ_NAMED_ENTITY = ComponentParameters.PARAM_READ_NAMED_ENTITY;
    @ConfigurationParameter(name = PARAM_READ_NAMED_ENTITY, mandatory = true, defaultValue = "true")
    private boolean readNamedEntity;

    /**
     * Write paragraphs annotations to the CAS.
     */
    public static final String PARAM_READ_PARAGRAPH = "readParagraph";
    @ConfigurationParameter(name = PARAM_READ_PARAGRAPH, mandatory = true, defaultValue = "true")
    private boolean readParagraph;

    /**
     * Use the xml:id attribute on the TEI elements as document ID. Mind that many TEI files may not
     * have this attribute on all TEI elements and you may end up with no document ID at all. Also
     * mind that the IDs should be unique.
     */
    public static final String PARAM_USE_XML_ID = "useXmlId";
    @ConfigurationParameter(name = PARAM_USE_XML_ID, mandatory = true, defaultValue = "false")
    private boolean useXmlId;

    /**
     * When not using the XML ID, use only the filename instead of the whole URL as ID. Mind that
     * the filenames should be unique in this case.
     */
    public static final String PARAM_USE_FILENAME_ID = "useFilenameId";
    @ConfigurationParameter(name = PARAM_USE_FILENAME_ID, mandatory = true, defaultValue = "false")
    private boolean useFilenameId;

    /**
     * Do not write <em>ignoreable whitespace</em> from the XML file to the CAS.
     */
    // REC: This does not seem to work. Maybe because SAXWriter does not generate this event?
    public static final String PARAM_OMIT_IGNORABLE_WHITESPACE = "omitIgnorableWhitespace";
    @ConfigurationParameter(name = PARAM_OMIT_IGNORABLE_WHITESPACE, mandatory = true, defaultValue = "false")
    private boolean omitIgnorableWhitespace;

    /**
     * Enable/disable type mapping.
     */
    public static final String PARAM_MAPPING_ENABLED = ComponentParameters.PARAM_MAPPING_ENABLED;
    @ConfigurationParameter(name = PARAM_MAPPING_ENABLED, mandatory = true, //
            defaultValue = ComponentParameters.DEFAULT_MAPPING_ENABLED)
    protected boolean mappingEnabled;

    /**
     * Location of the mapping file for part-of-speech tags to UIMA types.
     */
    public static final String PARAM_POS_MAPPING_LOCATION = ComponentParameters.PARAM_POS_MAPPING_LOCATION;
    @ConfigurationParameter(name = PARAM_POS_MAPPING_LOCATION, mandatory = false)
    protected String mappingPosLocation;

    /**
     * Use this part-of-speech tag set to use to resolve the tag set mapping instead of using the
     * tag set defined as part of the model meta data. This can be useful if a custom model is
     * specified which does not have such meta data, or it can be used in readers.
     */
    public static final String PARAM_POS_TAG_SET = ComponentParameters.PARAM_POS_TAG_SET;
    @ConfigurationParameter(name = PARAM_POS_TAG_SET, mandatory = false)
    protected String posTagset;

    /**
     * Interpret utterances "u" as sentenes "s". (EXPERIMENTAL)
     */
    public static final String PARAM_UTTERANCES_AS_SENTENCES = "utterancesAsSentences";
    @ConfigurationParameter(name = PARAM_UTTERANCES_AS_SENTENCES, mandatory = true, defaultValue = "false")
    private boolean utterancesAsSentences;

    /**
     * Trim the given elements (remote leading and trailing whitespace). DKPro Core usually expects
     * annotations to start and end at a non-whitespace character.
     */
    public static final String PARAM_ELEMENTS_TO_TRIM = "elementsToTrim";
    @ConfigurationParameter(name = PARAM_ELEMENTS_TO_TRIM, mandatory = true, defaultValue = {
            TAG_SUNIT, TAG_U, TAG_PARAGRAPH, TAG_RS, TAG_WORD, TAG_CHARACTER, TAG_MULTIWORD })
    private Set<String> elementsToTrim;

    private Iterator<Element> teiElementIterator;
    private Element currentTeiElement;
    private Resource currentResource;
    private int currentTeiElementNumber;

    private MappingProvider posMappingProvider;

    @Override
    public void initialize(UimaContext aContext) throws ResourceInitializationException
    {
        super.initialize(aContext);

        if (readPOS && !readToken) {
            throw new ResourceInitializationException(new IllegalArgumentException(
                    "Setting readPOS to 'true' requires writeToken to be 'true' too."));
        }

        try {
            // Init with an empty iterator
            teiElementIterator = asList(new Element[0]).iterator();

            // Make sure we know about the first element;
            nextTeiElement();
        }
        catch (CollectionException | IOException e) {
            throw new ResourceInitializationException(e);
        }

        posMappingProvider = createPosMappingProvider(this, mappingPosLocation, posTagset,
                getLanguage());
    }

    private void nextTeiElement() throws CollectionException, IOException
    {
        if (teiElementIterator == null) {
            currentTeiElement = null;
            return;
        }

        while (!teiElementIterator.hasNext() && super.hasNext()) {
            currentResource = nextFile();

            InputStream is = null;
            try {
                is = currentResource.getInputStream();

                if (currentResource.getPath().endsWith(".gz")) {
                    is = new GZIPInputStream(is);
                }

                InputSource source = new InputSource(is);
                source.setPublicId(currentResource.getLocation());
                source.setSystemId(currentResource.getLocation());

                var parser = newSaxParser(enableNamespaceSupport(newSaxParserFactory()));
                var reader = new SAXReader(parser.getXMLReader());
                Document xml = reader.read(source);

                final XPath teiPath = new Dom4jXPath("//tei:TEI");
                teiPath.addNamespace("tei", "http://www.tei-c.org/ns/1.0");

                List<Element> teiElements = teiPath.selectNodes(xml);

                // System.out.printf("Found %d TEI elements in %s.%n", teiElements.size(),
                // currentResource.getLocation());

                teiElementIterator = teiElements.iterator();
                currentTeiElementNumber = 0;
            }
            catch (DocumentException | ParserConfigurationException | SAXException e) {
                throw new IOException(e);
            }
            catch (JaxenException e) {
                throw new IOException(e);
            }
            finally {
                closeQuietly(is);
            }
        }

        currentTeiElement = teiElementIterator.hasNext() ? teiElementIterator.next() : null;
        currentTeiElementNumber++;

        if (!super.hasNext() && !teiElementIterator.hasNext()) {
            // Mark end of processing.
            teiElementIterator = null;
        }
    }

    @Override
    public boolean hasNext() throws IOException, CollectionException
    {
        return teiElementIterator != null || currentTeiElement != null;
    }

    @Override
    public void getNext(CAS aCAS) throws IOException, CollectionException
    {
        initCas(aCAS, currentResource);

        // Set up language
        if (getConfigParameterValue(PARAM_LANGUAGE) != null) {
            aCAS.setDocumentLanguage((String) getConfigParameterValue(PARAM_LANGUAGE));
        }

        // Configure mapping only now, because now the language is set in the CAS
        try {
            posMappingProvider.configure(aCAS);
        }
        catch (AnalysisEngineProcessException e1) {
            throw new IOException(e1);
        }

        try {
            JCas jcas = aCAS.getJCas();

            // Create handler
            Handler handler = newSaxHandler();
            handler.setJCas(jcas);
            handler.setLogger(getLogger());

            // Parse TEI text
            SAXWriter writer = new SAXWriter(handler);
            writer.write(currentTeiElement);
            handler.endDocument();
        }
        catch (CASException e) {
            throw new CollectionException(e);
        }
        catch (SAXException e) {
            throw new IOException(e);
        }

        // Move currentTeiElement to the next text
        nextTeiElement();
    }

    protected Handler newSaxHandler()
    {
        return new TeiHandler();
    }

    protected abstract static class Handler
        extends DefaultHandler
    {
        private JCas jcas;
        private Logger logger;

        public void setJCas(final JCas aJCas)
        {
            jcas = aJCas;
        }

        protected JCas getJCas()
        {
            return jcas;
        }

        public void setLogger(Logger aLogger)
        {
            logger = aLogger;
        }

        public Logger getLogger()
        {
            return logger;
        }
    }

    public class TeiHandler
        extends Handler
    {
        private String documentId = null;
        private boolean titleSet = false;
        private boolean inTextElement = false;
        private boolean captureText = false;
        private int paragraphStart = -1;
        private String paragraphId = null;
        private int sentenceStart = -1;
        private String sentenceId = null;
        private int tokenStart = -1;
        private String tokenId = null;
        private String posTag = null;
        private String lemma = null;
        private Stack<ConstituentWrapper> constituents = new Stack<>();
        private Stack<NamedEntity> namedEntities = new Stack<>();

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void endDocument() throws SAXException
        {
            getJCas().setDocumentText(buffer.toString());
        }

        protected StringBuilder getBuffer()
        {
            return buffer;
        }

        @Override
        public void startElement(String aUri, String aLocalName, String aName,
                Attributes aAttributes)
            throws SAXException
        {
            // System.out.printf("%b START %s %n", captureText, aLocalName);
            if (!inTextElement && TAG_TEI_DOC.equals(aName)) {
                if (useXmlId) {
                    documentId = aAttributes.getValue("xml:id");
                }
                else if (useFilenameId) {
                    documentId = FilenameUtils.getName(currentResource.getPath()) + "#"
                            + currentTeiElementNumber;
                }
                else {
                    documentId = currentResource.getPath() + "#" + currentTeiElementNumber;
                }
            }
            else if (!inTextElement && TAG_TITLE.equals(aName)) {
                captureText = true;
            }
            else if (TAG_TEXT.equals(aName)) {
                captureText = true;
                inTextElement = true;
            }
            else if (inTextElement && (TAG_SUNIT.equals(aName)
                    || (utterancesAsSentences && TAG_U.equals(aName)))) {
                sentenceStart = getBuffer().length();
                sentenceId = aAttributes.getValue(ATTR_XML_ID);
            }
            else if (inTextElement && TAG_PARAGRAPH.equals(aName)) {
                paragraphStart = getBuffer().length();
                paragraphId = aAttributes.getValue(ATTR_XML_ID);
            }
            else if (readNamedEntity && inTextElement && TAG_RS.equals(aName)) {
                NamedEntity ne = new NamedEntity(getJCas());
                ne.setBegin(getBuffer().length());
                ne.setValue(aAttributes.getValue(ATTR_TYPE));
                namedEntities.push(ne);
            }
            else if (readConstituent && inTextElement && TAG_PHRASE.equals(aName)) {
                if (constituents.isEmpty()) {
                    ROOT root = new ROOT(getJCas());
                    root.setConstituentType("ROOT");
                    constituents.push(new ConstituentWrapper(root));
                }

                Constituent constituent = new Constituent(getJCas());
                constituent.setConstituentType(aAttributes.getValue(ATTR_TYPE));
                constituent.setSyntacticFunction(aAttributes.getValue(ATTR_FUNCTION));
                constituents.push(new ConstituentWrapper(constituent));
            }
            else if (inTextElement && (TAG_WORD.equals(aName) || TAG_CHARACTER.equals(aName)
                    || TAG_MULTIWORD.equals(aName))) {
                tokenStart = getBuffer().length();
                tokenId = aAttributes.getValue(ATTR_XML_ID);
                if (StringUtils.isNotEmpty(aAttributes.getValue(ATTR_POS))) {
                    posTag = aAttributes.getValue(ATTR_POS);
                }
                else {
                    posTag = aAttributes.getValue(ATTR_TYPE);
                }
                lemma = aAttributes.getValue(ATTR_LEMMA);
            }
        }

        @Override
        public void endElement(String aUri, String aLocalName, String aName) throws SAXException
        {
            // System.out.printf("%b END %s %n", captureText, aLocalName);
            if (!inTextElement && TAG_TITLE.equals(aName)) {
                DocumentMetaData meta = DocumentMetaData.get(getJCas());
                // Read only the first title and hope it is the main title
                if (!titleSet) {
                    meta.setDocumentTitle(getBuffer().toString().trim());
                    titleSet = true;
                }
                meta.setDocumentId(documentId);
                getBuffer().setLength(0);
                captureText = false;
            }
            else if (TAG_TEXT.equals(aName)) {
                captureText = false;
                inTextElement = false;
            }
            else if (inTextElement && (TAG_SUNIT.equals(aName)
                    || (utterancesAsSentences && TAG_U.equals(aName)))) {
                if (readSentence) {
                    Sentence s = new Sentence(getJCas(), sentenceStart, getBuffer().length());
                    s.setId(trim(sentenceId));
                    if (elementsToTrim.contains(aName)) {
                        TrimUtils.trim(getBuffer(), s);
                    }
                    s.addToIndexes();
                }
                sentenceStart = -1;
                sentenceId = null;
            }
            else if (inTextElement && TAG_PARAGRAPH.equals(aName)) {
                if (readParagraph) {
                    Paragraph para = new Paragraph(getJCas(), paragraphStart, getBuffer().length());
                    para.setId(trim(paragraphId));
                    if (elementsToTrim.contains(aName)) {
                        TrimUtils.trim(getBuffer(), para);
                    }
                    para.addToIndexes();
                }
                paragraphStart = -1;
                paragraphId = null;
            }
            else if (readNamedEntity && inTextElement && TAG_RS.equals(aName)) {
                NamedEntity ne = namedEntities.pop();
                ne.setEnd(getBuffer().length());
                if (elementsToTrim.contains(aName)) {
                    TrimUtils.trim(getBuffer(), ne);
                }
                ne.addToIndexes();
            }
            else if (readConstituent && inTextElement && TAG_PHRASE.equals(aName)) {
                ConstituentWrapper wrapper = constituents.pop();
                wrapper.constituent.setBegin(wrapper.children.get(0).getBegin());
                wrapper.constituent
                        .setEnd(wrapper.children.get(wrapper.children.size() - 1).getEnd());
                if (!constituents.isEmpty()) {
                    ConstituentWrapper parent = constituents.peek();
                    wrapper.constituent.setParent(parent.constituent);
                    parent.children.add(wrapper.constituent);
                }
                wrapper.constituent.setChildren(
                        FSCollectionFactory.createFSArray(getJCas(), wrapper.children));
                wrapper.constituent.addToIndexes();

                // Close off the ROOT
                if (constituents.peek().constituent instanceof ROOT) {
                    ConstituentWrapper rootWrapper = constituents.pop();
                    rootWrapper.constituent.setBegin(wrapper.children.get(0).getBegin());
                    rootWrapper.constituent
                            .setEnd(wrapper.children.get(wrapper.children.size() - 1).getEnd());
                    rootWrapper.constituent.setChildren(
                            FSCollectionFactory.createFSArray(getJCas(), rootWrapper.children));
                    rootWrapper.constituent.addToIndexes();
                }
            }
            else if (inTextElement && (TAG_WORD.equals(aName) || TAG_CHARACTER.equals(aName)
                    || TAG_MULTIWORD.equals(aName))) {
                if (isNotBlank(getBuffer().substring(tokenStart, getBuffer().length()))) {
                    Token token = new Token(getJCas(), tokenStart, getBuffer().length());
                    token.setId(trim(tokenId));

                    if (elementsToTrim.contains(aName)) {
                        TrimUtils.trim(getBuffer(), token);
                    }

                    if (posTag != null && readPOS) {
                        Type posTagType = posMappingProvider.getTagType(posTag);
                        POS pos = (POS) getJCas().getCas().createAnnotation(posTagType,
                                token.getBegin(), token.getEnd());
                        pos.setPosValue(posTag);
                        POSUtils.assignCoarseValue(pos);
                        pos.addToIndexes();
                        token.setPos(pos);
                    }

                    if (lemma != null && readLemma) {
                        Lemma l = new Lemma(getJCas(), token.getBegin(), token.getEnd());
                        l.setValue(lemma);
                        l.addToIndexes();
                        token.setLemma(l);
                    }

                    // FIXME: if readToken is disabled, the JCas wrapper should not be generated
                    // at all!
                    if (readToken) {
                        if (!constituents.isEmpty()) {
                            ConstituentWrapper parent = constituents.peek();
                            token.setParent(parent.constituent);
                            parent.children.add(token);
                        }

                        token.addToIndexes();
                    }
                }

                tokenStart = -1;
                tokenId = null;
            }
        }

        @Override
        public void characters(char[] aCh, int aStart, int aLength) throws SAXException
        {
            if (captureText) {
                buffer.append(aCh, aStart, aLength);
            }
        }

        @Override
        public void ignorableWhitespace(char[] aCh, int aStart, int aLength) throws SAXException
        {
            if (captureText && !omitIgnorableWhitespace) {
                buffer.append(aCh, aStart, aLength);
            }
        }
    }

    private static class ConstituentWrapper
    {
        public Constituent constituent;
        public List<Annotation> children = new ArrayList<Annotation>();

        public ConstituentWrapper(Constituent aConstituent)
        {
            constituent = aConstituent;
        }
    }
}
