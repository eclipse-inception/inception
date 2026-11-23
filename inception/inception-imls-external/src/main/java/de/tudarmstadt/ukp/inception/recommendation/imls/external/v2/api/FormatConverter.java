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

import static de.tudarmstadt.ukp.inception.recommendation.api.RecommendationService.FEATURE_NAME_IS_PREDICTION;
import static java.util.Collections.singletonMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.Feature;
import org.apache.uima.cas.Type;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.fit.util.CasUtil;

import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;

public class FormatConverter
{
    public static final String SENTENCE_LAYER = "t.sentence";
    public static final String TOKEN_LAYER = "t.token";
    public static final String TARGET_LAYER = "t.annotation";
    public static final String TARGET_FEATURE = "f.value";

    public Document documentFromCas(CAS aCas, String aLayerName, String aFeatureName, long aVersion)
    {
        String text = aCas.getDocumentText();
        Map<String, List<Annotation>> annotations = new HashMap<>();

        // Add sentences
        Type sentenceType = CasUtil.getAnnotationType(aCas, Sentence.class);
        List<Annotation> sentences = new ArrayList<>();
        for (AnnotationFS sentence : CasUtil.select(aCas, sentenceType)) {
            sentences.add(new Annotation(sentence.getBegin(), sentence.getEnd()));
        }
        annotations.put(SENTENCE_LAYER, sentences);

        // Add tokens
        Type tokenType = CasUtil.getAnnotationType(aCas, Token.class);
        List<Annotation> tokens = new ArrayList<>();
        for (AnnotationFS token : CasUtil.select(aCas, tokenType)) {
            tokens.add(new Annotation(token.getBegin(), token.getEnd()));
        }
        annotations.put(TOKEN_LAYER, tokens);

        // Add targets
        Type targetType = CasUtil.getAnnotationType(aCas, aLayerName);
        Feature feature = targetType.getFeatureByBaseName(aFeatureName);
        List<Annotation> targets = new ArrayList<>();
        for (AnnotationFS target : CasUtil.select(aCas, targetType)) {
            String featureValue = target.getFeatureValueAsString(feature);
            Map<String, String> featureValues = singletonMap(TARGET_FEATURE, featureValue);
            targets.add(new Annotation(target.getBegin(), target.getEnd(), featureValues));
        }
        annotations.put(TARGET_LAYER, targets);

        return new Document(text, annotations, aVersion);
    }

    public void loadIntoCas(Document aDocument, String aLayerName, String aFeatureName, CAS aCas)
    {
        Type targetType = CasUtil.getAnnotationType(aCas, aLayerName);
        Feature feature = targetType.getFeatureByBaseName(aFeatureName);
        Feature isPredicted = targetType.getFeatureByBaseName(FEATURE_NAME_IS_PREDICTION);

        List<Annotation> annotations = aDocument.getAnnotations().getOrDefault(TARGET_LAYER,
                Collections.emptyList());

        for (Annotation annotation : annotations) {
            int begin = annotation.getBegin();
            int end = annotation.getEnd();
            AnnotationFS fs = aCas.createAnnotation(targetType, begin, end);
            String featureValue = annotation.getFeatures().get(TARGET_FEATURE);
            fs.setStringValue(feature, featureValue);
            if (isPredicted != null) {
                fs.setBooleanValue(isPredicted, true);
            }
            aCas.addFsToIndexes(fs);
        }
    }
}
