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
package de.tudarmstadt.ukp.inception.recommendation.imls.external.v2;

import static de.tudarmstadt.ukp.inception.recommendation.api.recommender.TrainingCapability.TRAINING_NOT_SUPPORTED;
import static de.tudarmstadt.ukp.inception.recommendation.api.recommender.TrainingCapability.TRAINING_REQUIRED;
import static de.tudarmstadt.ukp.inception.support.uima.WebAnnoCasUtil.getDocumentTitle;
import static de.tudarmstadt.ukp.inception.support.uima.WebAnnoCasUtil.getRealCas;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.uima.cas.CAS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.security.model.User;
import de.tudarmstadt.ukp.inception.annotation.storage.CasMetadataUtils;
import de.tudarmstadt.ukp.inception.recommendation.api.evaluation.DataSplitter;
import de.tudarmstadt.ukp.inception.recommendation.api.evaluation.EvaluationResult;
import de.tudarmstadt.ukp.inception.recommendation.api.model.Recommender;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.PredictionContext;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommendationEngine;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommendationException;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommenderContext;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.TrainingCapability;
import de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.api.Document;
import de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.api.DocumentList;
import de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.api.ExternalRecommenderApiException;
import de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.api.ExternalRecommenderV2Api;
import de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.api.FormatConverter;
import de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.config.ExternalRecommenderProperties;
import de.tudarmstadt.ukp.inception.support.uima.Range;

public class ExternalRecommender
    extends RecommendationEngine
{
    private final static Logger LOG = LoggerFactory.getLogger(ExternalRecommender.class);

    public static final RecommenderContext.Key<Boolean> KEY_TRAINING_COMPLETE = new RecommenderContext.Key<>(
            "training_complete");

    private final ExternalRecommenderProperties properties;
    private final ExternalRecommenderTraits traits;
    private final ExternalRecommenderV2Api api;

    public ExternalRecommender(ExternalRecommenderProperties aProperties, Recommender aRecommender,
            ExternalRecommenderTraits aTraits)
    {
        super(aRecommender);

        properties = aProperties;
        traits = aTraits;
        api = new ExternalRecommenderV2Api(URI.create(traits.getRemoteUrl()),
                properties.getConnectTimeout());
    }

    @Override
    public void train(RecommenderContext aContext, List<CAS> aCasses) throws RecommendationException
    {
        if (aContext.getUser().isEmpty()) {
            LOG.warn("No user found in context, skipping training...");
            return;
        }

        User user = aContext.getUser().get();
        Project project = recommender.getProject();
        String datasetName = buildDatasetName(project, user);
        String classifierName = buildClassifierName();
        String modelName = buildModelName(project, user);

        api.createDataset(datasetName);
        synchronizeDocuments(aCasses, datasetName);
        api.trainOnDataset(classifierName, modelName, datasetName);

        aContext.put(KEY_TRAINING_COMPLETE, true);
    }

    private void synchronizeDocuments(List<CAS> aCasses, String aDatasetName)
        throws RecommendationException
    {
        LOG.debug("Synchronizing documents for [{}]", aDatasetName);

        // Get info about remote documents
        DocumentList documentList = api.listDocumentsInDataset(aDatasetName);
        Map<String, Long> remoteVersions = new HashMap<>();
        if (documentList.getNames().size() != documentList.getVersions().size()) {
            throw new RecommendationException(
                    "Names and versions in document list have unequal size");
        }

        for (int i = 0; i < documentList.getNames().size(); i++) {
            remoteVersions.put(documentList.getNames().get(i), documentList.getVersions().get(i));
        }

        // Sync documents
        List<CAS> documentsToSend = new ArrayList<>();
        Set<String> seenLocalDocuments = new HashSet<>();

        for (CAS cas : aCasses) {
            String documentName = getDocumentName(cas);
            seenLocalDocuments.add(documentName);

            // If the document is not known to the remote server, then we need to update it
            if (!remoteVersions.containsKey(documentName)) {
                documentsToSend.add(cas);
                continue;
            }

            // If the version is unclear or our local version is newer, then we need to update it
            long localVersion = getVersion(cas);
            long remoteVersion = remoteVersions.getOrDefault(documentName, -1L);
            if (localVersion == -1L || remoteVersion == -1L || localVersion > remoteVersion) {
                documentsToSend.add(cas);
                continue;
            }
        }

        // All documents that have not been seen locally but are listed remotely need to be deleted
        Set<String> documentsToDelete = remoteVersions.keySet();
        documentsToDelete.removeAll(seenLocalDocuments);

        for (String name : documentsToDelete) {
            api.deleteDocumentFromDataset(aDatasetName, name);
        }

        LOG.debug("Deleted [{}] documents", documentsToDelete.size());

        // Finally, send new/updated documents
        FormatConverter converter = new FormatConverter();
        for (CAS cas : documentsToSend) {
            var documentName = getDocumentName(cas);
            var version = getVersion(cas);
            var document = converter.documentFromCas(cas, recommender.getLayer().getName(),
                    recommender.getFeature().getName(), version);

            api.addDocumentToDataset(aDatasetName, documentName, document);
        }

        LOG.debug("Sent [{}] documents", documentsToSend.size());
    }

    @Override
    public Range predict(PredictionContext aContext, CAS aCas, int aBegin, int aEnd)
        throws RecommendationException
    {
        CAS cas = getRealCas(aCas);

        if (aContext.getUser().isEmpty()) {
            LOG.warn("No user found in context, skipping predictions...");
            return Range.UNDEFINED;
        }

        long version = 0;

        var converter = new FormatConverter();
        var layerName = recommender.getLayer().getName();
        var featureName = recommender.getFeature().getName();

        var request = converter.documentFromCas(cas, layerName, featureName, version);

        var modelName = buildModelName(recommender.getProject(), aContext.getUser().get());
        var classifierName = traits.getClassifierInfo().getName();

        try {
            Document response = api.predict(classifierName, modelName, request);
            converter.loadIntoCas(response, layerName, featureName, cas);
        }
        catch (ExternalRecommenderApiException e) {
            LOG.error("Could not obtain predictions, skipping...");
        }

        return new Range(aBegin, aEnd);
    }

    @Override
    public EvaluationResult evaluate(List<CAS> aCasses, DataSplitter aDataSplitter)
        throws RecommendationException
    {
        return null;
    }

    @Override
    public boolean isReadyForPrediction(RecommenderContext aContext)
    {
        if (traits.isTrainable()) {
            return aContext.get(KEY_TRAINING_COMPLETE).orElse(false);
        }
        else {
            return true;
        }
    }

    @Override
    public int estimateSampleCount(List<CAS> aCasses)
    {
        return 0;
    }

    @Override
    public TrainingCapability getTrainingCapability()
    {
        if (traits.isTrainable()) {
            //
            // return TRAINING_SUPPORTED;
            // We need to get at least one training CAS because we need to extract the type system
            return TRAINING_REQUIRED;
        }
        else {
            return TRAINING_NOT_SUPPORTED;
        }
    }

    private String buildClassifierName()
    {
        return traits.getClassifierInfo().getName();
    }

    private String buildModelName(Project aProject, User aUser)
    {
        return aProject.getId() + "_" + aUser.getUsername() + "_" + recommender.getId();
    }

    private String buildDatasetName(Project aProject, User aUser)
    {
        return aProject.getId() + "_" + aUser.getUsername();
    }

    private String getDocumentName(CAS aCas)
    {
        return CasMetadataUtils.getSourceDocumentName(aCas).orElse(getDocumentTitle(aCas));
    }

    private long getVersion(CAS aCas)
    {
        return CasMetadataUtils.getLastChanged(aCas);
    }

}
