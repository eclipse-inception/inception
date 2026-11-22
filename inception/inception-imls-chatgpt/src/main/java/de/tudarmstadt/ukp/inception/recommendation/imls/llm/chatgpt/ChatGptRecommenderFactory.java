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
package de.tudarmstadt.ukp.inception.recommendation.imls.llm.chatgpt;

import static org.apache.uima.cas.CAS.TYPE_NAME_STRING;

import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.util.ListModel;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.inception.annotation.layer.document.api.DocumentMetadataLayerSupport;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.SpanLayerSupport;
import de.tudarmstadt.ukp.inception.recommendation.api.model.Recommender;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommendationEngine;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommendationEngineFactoryImplBase;
import de.tudarmstadt.ukp.inception.recommendation.imls.llm.AnnotationTaskCodecExtensionPoint;
import de.tudarmstadt.ukp.inception.recommendation.imls.llm.chatgpt.client.ChatCompletionRequest;
import de.tudarmstadt.ukp.inception.recommendation.imls.llm.client.LlmChatClientExtensionPoint;
import de.tudarmstadt.ukp.inception.recommendation.imls.llm.support.preset.Presets;
import de.tudarmstadt.ukp.inception.schema.api.AnnotationSchemaService;

public class ChatGptRecommenderFactory
    extends RecommendationEngineFactoryImplBase<ChatGptRecommenderTraits>
{
    // This is a string literal so we can rename/refactor the class without it changing its ID
    // and without the database starting to refer to non-existing recommendation tools.
    public static final String ID = "de.tudarmstadt.ukp.inception.recommendation.imls.chatgpt.ChatGptRecommender";

    private final AnnotationSchemaService schemaService;
    private final AnnotationTaskCodecExtensionPoint responseExtractorExtensionPoint;
    private final LlmChatClientExtensionPoint chatClientExtensionPoint;

    public ChatGptRecommenderFactory(AnnotationSchemaService aSchemaService,
            AnnotationTaskCodecExtensionPoint aResponseExtractorExtensionPoint,
            LlmChatClientExtensionPoint aChatClientExtensionPoint)
    {
        schemaService = aSchemaService;
        responseExtractorExtensionPoint = aResponseExtractorExtensionPoint;
        chatClientExtensionPoint = aChatClientExtensionPoint;
    }

    @Override
    public String getId()
    {
        return ID;
    }

    @Override
    public String getName()
    {
        return "LLM via OpenAI-compatible API";
    }

    @Override
    public RecommendationEngine build(Recommender aRecommender)
    {
        var traits = readTraits(aRecommender);
        return new ChatGptRecommender(aRecommender, traits, schemaService,
                responseExtractorExtensionPoint, chatClientExtensionPoint);
    }

    @Override
    public boolean accepts(AnnotationLayer aLayer)
    {
        if (aLayer == null) {
            return false;
        }

        return (SpanLayerSupport.TYPE.equals(aLayer.getType())
                || DocumentMetadataLayerSupport.TYPE.equals(aLayer.getType()));
    }

    @Override
    public boolean accepts(AnnotationFeature aFeature)
    {
        if (aFeature == null) {
            return false;
        }

        return accepts(aFeature.getLayer()) && TYPE_NAME_STRING.equals(aFeature.getType());
    }

    @Override
    public ChatGptRecommenderTraitsEditor createTraitsEditor(String aId, IModel<Recommender> aModel)
    {
        return new ChatGptRecommenderTraitsEditor(aId, aModel,
                new ListModel<>(Presets.getPresets()),
                new ListModel<>(ChatCompletionRequest.getAllOptions()));
    }

    @Override
    public ChatGptRecommenderTraits createTraits()
    {
        return new ChatGptRecommenderTraits();
    }

    @Override
    public boolean isEvaluable()
    {
        return false;
    }

    @Override
    public boolean isMultipleRecommendationProvider()
    {
        return false;
    }

    @Override
    public boolean isInteractive(Recommender aRecommender)
    {
        return readTraits(aRecommender).isInteractive();
    }

    @Override
    public Panel createInteractionPanel(String aId, IModel<Recommender> aModel)
    {
        return new ChatGptInteractionPanel(aId, aModel, new ListModel<>(Presets.getPresets()));
    }
}
