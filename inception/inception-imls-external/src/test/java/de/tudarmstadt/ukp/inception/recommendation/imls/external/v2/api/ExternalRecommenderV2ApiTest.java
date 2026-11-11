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

import static de.tudarmstadt.ukp.inception.recommendation.imls.external.util.Fixtures.loadAlaskaCas;
import static de.tudarmstadt.ukp.inception.support.json.JSONUtil.toPrettyJsonString;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.uima.cas.CAS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Requires starting a test server")
public class ExternalRecommenderV2ApiTest
{

    /// ********************************************* In order to run these, you need to install the
    /// test dependencies pip install -e ".[all]" and then to run make inception_test in the galahad
    /// repository *********************************************

    private ExternalRecommenderV2Api sut;

    private static final String CLASSIFIER_ID = "spacy_ner";

    @BeforeEach
    public void setUp() throws Exception
    {
        sut = new ExternalRecommenderV2Api(URI.create("http://localhost:8000"));
        purgeServer();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        purgeServer();
        sut = null;
    }

    // Dataset

    @Test
    public void testListDatasets() throws Exception
    {
        List<String> datasetNames = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String name = "test_dataset_" + (i + 1);
            datasetNames.add(name);
            sut.createDataset(name);
        }

        DatasetList result = sut.listDatasets();
        assertThat(result.getNames()).isEqualTo(datasetNames);
    }

    @Test
    public void testCreateDataset() throws Exception
    {
        sut.createDataset("test_dataset");
        assertThat(sut.listDatasets().getNames()).isEqualTo(List.of("test_dataset"));
    }

    @Test
    public void testDeleteDataset() throws Exception
    {
        sut.createDataset("test_dataset");
        assertThat(sut.listDatasets().getNames()).isEqualTo(List.of("test_dataset"));

        sut.deleteDataset("test_dataset");
        assertThat(sut.listDatasets().getNames()).isEmpty();
    }

    // Documents

    @Test
    public void testListDocumentsInDataset() throws Exception
    {
        String datasetId = "test_dataset";
        sut.createDataset("test_dataset");

        List<String> documentNames = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String name = "test_document_" + (i + 1);
            documentNames.add(name);
            sut.addDocumentToDataset("test_dataset", name, buildAlaskaDocument());
        }

        DocumentList result = sut.listDocumentsInDataset(datasetId);

        assertThat(result.getNames()).isEqualTo(documentNames);
        assertThat(result.getNames()).hasSameSizeAs(result.getVersions());
    }

    @Test
    public void testAddDocumentToDataset() throws Exception
    {
        String datasetId = "test_dataset";
        sut.createDataset("test_dataset");
        sut.addDocumentToDataset("test_dataset", "test_document", buildAlaskaDocument());

        DocumentList result = sut.listDocumentsInDataset(datasetId);

        assertThat(result.getNames()).isEqualTo(List.of("test_document"));
        assertThat(result.getNames()).hasSameSizeAs(result.getVersions());
    }

    @Test
    public void testDeleteDocumentFromDataset() throws Exception
    {
        sut.createDataset("test_dataset");
        sut.addDocumentToDataset("test_dataset", "test_document", buildAlaskaDocument());
        DocumentList result = sut.listDocumentsInDataset("test_dataset");
        assertThat(result.getNames()).isEqualTo(List.of("test_document"));

        sut.deleteDocumentFromDataset("test_dataset", "test_document");
        assertThat(sut.listDocumentsInDataset("test_dataset").getNames()).isEmpty();
    }

    // Classifier

    @Test
    public void testGetAvailableClassifiers() throws Exception
    {
        List<ClassifierInfo> availableClassifiers = sut.getAvailableClassifiers();
        List<String> classifierNames = availableClassifiers.stream() //
                .map(ClassifierInfo::getName) //
                .collect(Collectors.toList());

        assertThat(classifierNames).isEqualTo(List.of("sklearn1", "sklearn2", "spacy_ner"));
    }

    @Test
    public void testGetClassifierInfo() throws Exception
    {
        ClassifierInfo classifierInfo = sut.getClassifierInfo(CLASSIFIER_ID);

        assertThat(classifierInfo.getName()).isEqualTo(CLASSIFIER_ID);
    }

    // Train/Predict

    @Test
    public void testTrainOnDataset() throws Exception
    {
        sut.createDataset("test_dataset");
        sut.addDocumentToDataset("test_dataset", "test_document", buildAlaskaDocument());

        sut.trainOnDataset("sklearn1", "test_model", "test_dataset");
    }

    @Test
    public void testPredictDocument() throws Exception
    {
        Document document = buildAlaskaDocument();

        Document response = sut.predict(CLASSIFIER_ID, "test_model", document);

        System.out.println(toPrettyJsonString(response));
    }

    private void purgeServer() throws Exception
    {
        // Delete existing datasets
        DatasetList datasetList = sut.listDatasets();
        for (String datasetName : datasetList.getNames()) {
            sut.deleteDataset(datasetName);
        }
    }

    private Document buildAlaskaDocument() throws Exception
    {
        FormatConverter converter = new FormatConverter();
        CAS cas = loadAlaskaCas();
        return converter.documentFromCas(cas,
                "de.tudarmstadt.ukp.dkpro.core.api.ner.type.NamedEntity", "value", 0);
    }
}
