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

import static de.tudarmstadt.ukp.inception.support.json.JSONUtil.fromJsonString;
import static de.tudarmstadt.ukp.inception.support.json.JSONUtil.getObjectMapper;
import static de.tudarmstadt.ukp.inception.support.json.JSONUtil.toJsonString;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.StringJoiner;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.type.TypeReference;

public class ExternalRecommenderV2Api
{
    private static final Logger LOG = LoggerFactory.getLogger(ExternalRecommenderV2Api.class);

    private final HttpClient client;
    private final String remoteUrl;

    public ExternalRecommenderV2Api(URI aRemoteUrl, Duration aTimeout)
    {
        remoteUrl = StringUtils.removeEnd(aRemoteUrl.toString(), "/");

        client = HttpClient.newBuilder() //
                .version(HttpClient.Version.HTTP_1_1) //
                .connectTimeout(aTimeout).build();
    }

    public ExternalRecommenderV2Api(URI aRemoteUrl)
    {
        this(aRemoteUrl, Duration.ofSeconds(10));
    }

    public boolean isReachable()
    {
        URI url = urlFor("ping");
        HttpRequest request = HttpRequest.newBuilder() //
                .uri(url) //
                .GET() //
                .build();

        try {
            HttpResponse<Void> response = client.send(request, BodyHandlers.discarding());
            return response.statusCode() == 200;
        }
        catch (Exception e) {
            LOG.warn("Error while pinging external recommender server [{}]", url, e);
            return false;
        }
    }

    // Dataset

    public DatasetList listDatasets() throws ExternalRecommenderApiException
    {
        URI url = urlFor("dataset");
        HttpRequest request = HttpRequest.newBuilder() //
                .uri(url) //
                .GET() //
                .build();

        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 200) {
                throw errorf("listDatasets - Unexpected status code [%d]: [%s]", status,
                        response.body());
            }
            return fromJsonString(DatasetList.class, response.body());
        }
        catch (IOException | InterruptedException e) {
            throw error("Error while listing datasets", e);
        }
    }

    public void createDataset(String aDatasetId) throws ExternalRecommenderApiException
    {
        URI url = urlFor("dataset", aDatasetId);
        HttpRequest request = HttpRequest.newBuilder() //
                .uri(url) //
                .PUT(BodyPublishers.noBody()) //
                .build();

        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 204 && status != 409) {
                throw errorf("createDataset- Unexpected status code [%d]: [%s]", status,
                        response.body());
            }
        }
        catch (IOException | InterruptedException e) {
            throw errorf("Error while creating dataset [%s]", e, aDatasetId);
        }
    }

    public void deleteDataset(String aDatasetId) throws ExternalRecommenderApiException
    {
        URI url = urlFor("dataset", aDatasetId);
        HttpRequest request = HttpRequest.newBuilder() //
                .uri(url) //
                .DELETE() //
                .build();

        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 204 && status != 404) {
                throw errorf("deleteDataset - Unexpected status code [%d]: [%s]", status,
                        response.body());
            }
        }
        catch (IOException | InterruptedException e) {
            throw errorf("Error while deleting dataset [%s]", e, aDatasetId);
        }
    }

    // Documents

    public DocumentList listDocumentsInDataset(String aDatasetId)
        throws ExternalRecommenderApiException
    {
        URI url = urlFor("dataset", aDatasetId);
        HttpRequest request = HttpRequest.newBuilder() //
                .uri(url) //
                .GET() //
                .build();

        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            int status = response.statusCode();

            if (status != 200) {
                throw errorf("listDocumentsInDataset: Unexpected status code [%d]: [%s]", status,
                        response.body());
            }
            return fromJsonString(DocumentList.class, response.body());
        }
        catch (IOException | InterruptedException e) {
            throw errorf("Error while listing documents for dataset [%s]", e, aDatasetId);
        }
    }

    public void addDocumentToDataset(String aDatasetId, String aDocumentId, Document aDocument)
        throws ExternalRecommenderApiException
    {
        try {
            URI url = urlFor("dataset", aDatasetId, aDocumentId);
            HttpRequest request = HttpRequest.newBuilder() //
                    .uri(url) //
                    .header("Content-Type", "application/json") //
                    .PUT(BodyPublishers.ofString(toJsonString(aDocument))) //
                    .build();

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 204) {
                throw errorf("addDocumentToDataset: Unexpected status code [%d]: [%s]", status,
                        response.body());
            }
        }
        catch (IOException | InterruptedException e) {
            throw errorf("Error while adding document [%s] to dataset [%s]", e, aDocumentId,
                    aDatasetId);
        }
    }

    public void deleteDocumentFromDataset(String aDatasetId, String aDocumentId)
        throws ExternalRecommenderApiException
    {
        URI url = urlFor("dataset", aDatasetId, aDocumentId);
        HttpRequest request = HttpRequest.newBuilder() //
                .uri(url) //
                .DELETE() //
                .build();

        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 204 && status != 404) {
                throw errorf("deleteDocumentFromDataset - Unexpected status code [%d]: [%s]",
                        status, response.body());
            }
        }
        catch (IOException | InterruptedException e) {
            throw errorf("Error while deleting document [%s] from dataset [%s]", e, aDocumentId,
                    aDatasetId);
        }
    }

    // Classifier

    public List<ClassifierInfo> getAvailableClassifiers() throws ExternalRecommenderApiException
    {
        URI url = urlFor("classifier");
        HttpRequest request = HttpRequest.newBuilder() //
                .uri(url) //
                .GET() //
                .build();

        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            return getObjectMapper().readValue(response.body(), new TypeReference<>()
            {
            });

        }
        catch (IOException | InterruptedException e) {
            throw error("Error while getting available classifier", e);
        }
    }

    public ClassifierInfo getClassifierInfo(String aClassifierId)
        throws ExternalRecommenderApiException
    {
        URI url = urlFor("classifier", aClassifierId);
        HttpRequest request = HttpRequest.newBuilder() //
                .uri(url) //
                .GET() //
                .build();

        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            return fromJsonString(ClassifierInfo.class, response.body());
        }
        catch (Exception e) {
            throw errorf("Error while getting info for classifier [%s]", e, aClassifierId);
        }
    }

    public void trainOnDataset(String aClassifierId, String aModelId, String aDatasetId)
        throws ExternalRecommenderApiException
    {
        URI url = urlFor("classifier", aClassifierId, aModelId, "train", aDatasetId);
        HttpRequest request = HttpRequest.newBuilder() //
                .uri(url) //
                .POST(BodyPublishers.noBody()) //
                .build();

        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 202 && status != 429) {
                throw errorf("trainOnDataset - Unexpected status code [%d]: [%s]", status,
                        response.body());
            }
        }
        catch (IOException | InterruptedException e) {
            throw errorf("Error while training on dataset [%s]", e, aDatasetId);
        }
    }

    public Document predict(String aClassifierId, String aModelId, Document aDocument)
        throws ExternalRecommenderApiException
    {
        URI url = urlFor("classifier", aClassifierId, aModelId, "predict");

        try {
            HttpRequest request = HttpRequest.newBuilder() //
                    .uri(url) //
                    .header("Content-Type", "application/json") //
                    .POST(BodyPublishers.ofString(toJsonString(aDocument))) //
                    .build();
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            LOG.info("Predicting finished with status code [{}]", response.statusCode());

            if (response.statusCode() == 200) {
                return fromJsonString(Document.class, response.body());
            }
            else {
                String msg = "Error while predicting: " + response.body();
                LOG.error(msg);
                throw new ExternalRecommenderApiException(msg);
            }
        }
        catch (IOException | InterruptedException e) {
            throw errorf("Error while predicting [%s] [%s]", e, aClassifierId, aModelId);
        }
    }

    private ExternalRecommenderApiException errorf(String aFormatString, Object... aArgs)
    {
        String message = String.format(aFormatString, aArgs);
        LOG.error(message);
        return new ExternalRecommenderApiException(message);
    }

    private ExternalRecommenderApiException errorf(String aFormatString, Exception aCause,
            Object... aArgs)
    {
        String message = String.format(aFormatString, aArgs);
        LOG.error(message);
        return new ExternalRecommenderApiException(message, aCause);
    }

    private ExternalRecommenderApiException error(String aMessage, Exception aCause)
    {
        LOG.error(aMessage, aCause);
        return new ExternalRecommenderApiException(aMessage, aCause);
    }

    private URI urlFor(String... aParts)
    {
        StringJoiner joiner = new StringJoiner("/");
        for (String part : aParts) {
            joiner.add(encode(part));
        }

        return URI.create(remoteUrl + "/" + joiner);
    }

    private String encode(String aString)
    {
        return URLEncoder.encode(aString, Charset.defaultCharset());
    }

}
