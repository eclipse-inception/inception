/*
 * Copyright 2018
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
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
package de.tudarmstadt.ukp.inception.recommendation.imls.opennlp.ner;

import static de.tudarmstadt.ukp.clarin.webanno.model.AnchoringMode.SENTENCES;
import static de.tudarmstadt.ukp.clarin.webanno.model.AnchoringMode.SINGLE_TOKEN;
import static de.tudarmstadt.ukp.clarin.webanno.model.AnchoringMode.TOKENS;
import static java.util.Arrays.asList;
import static org.apache.uima.cas.CAS.TYPE_NAME_BOOLEAN;
import static org.apache.uima.cas.CAS.TYPE_NAME_STRING;

import org.apache.wicket.model.IModel;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.SpanLayerSupport;
import de.tudarmstadt.ukp.inception.recommendation.api.model.Recommender;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.AbstractTraitsEditor;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommendationEngine;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommendationEngineFactoryImplBase;

public class OpenNlpNerRecommenderFactory
    extends RecommendationEngineFactoryImplBase<OpenNlpNerRecommenderTraits>
{
    // This is a string literal so we can rename/refactor the class without it changing its ID
    // and without the database starting to refer to non-existing recommendation tools.
    public static final String ID = "de.tudarmstadt.ukp.inception.recommendation.imls.opennlp.ner.OpenNlpNerClassificationTool";

    @Override
    public String getId()
    {
        return ID;
    }

    @Override
    public RecommendationEngine build(Recommender aRecommender)
    {
        var traits = new OpenNlpNerRecommenderTraits();
        return new OpenNlpNerRecommender(aRecommender, traits);
    }

    @Override
    public String getName()
    {
        return "Multi-Token Sequence Classifier (Apache OpenNLP NER)";
    }

    @Override
    public boolean accepts(AnnotationLayer aLayer)
    {
        if (aLayer == null) {
            return false;
        }

        return (asList(SINGLE_TOKEN, TOKENS, SENTENCES).contains(aLayer.getAnchoringMode()))
                && SpanLayerSupport.TYPE.equals(aLayer.getType());
    }

    @Override
    public boolean accepts(AnnotationFeature aFeature)
    {
        if (aFeature == null) {
            return false;
        }

        if (!accepts(aFeature.getLayer())) {
            return false;
        }

        return asList(TYPE_NAME_STRING, TYPE_NAME_BOOLEAN).contains(aFeature.getType())
                || aFeature.isVirtualFeature();
    }

    @Override
    public AbstractTraitsEditor createTraitsEditor(String aId, IModel<Recommender> aModel)
    {
        return new OpenNlpNerRecommenderTraitsEditor(aId, aModel);
    }

    @Override
    public OpenNlpNerRecommenderTraits createTraits()
    {
        return new OpenNlpNerRecommenderTraits();
    }

    @Override
    public boolean isModelExportSupported()
    {
        return true;
    }

    @Override
    public String getExportModelName(Recommender aRecommender)
    {
        return "model.bin";
    }
}
