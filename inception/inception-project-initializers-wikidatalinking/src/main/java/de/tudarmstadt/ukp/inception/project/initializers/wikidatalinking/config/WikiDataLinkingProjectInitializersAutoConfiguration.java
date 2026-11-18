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
package de.tudarmstadt.ukp.inception.project.initializers.wikidatalinking.config;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.Map;

import org.apache.wicket.request.resource.PackageResourceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import de.tudarmstadt.ukp.clarin.webanno.security.UserDao;
import de.tudarmstadt.ukp.inception.annotation.feature.string.StringFeatureSupport;
import de.tudarmstadt.ukp.inception.documents.api.DocumentService;
import de.tudarmstadt.ukp.inception.kb.KnowledgeBaseService;
import de.tudarmstadt.ukp.inception.kb.config.KnowledgeBaseServiceAutoConfiguration;
import de.tudarmstadt.ukp.inception.kb.yaml.KnowledgeBaseProfile;
import de.tudarmstadt.ukp.inception.project.initializers.neannotation.EntityAnnotationProjectInitializer;
import de.tudarmstadt.ukp.inception.project.initializers.neannotation.NamedEntitySampleDataTagSetInitializer;
import de.tudarmstadt.ukp.inception.project.initializers.neannotation.NamedEntitySequenceClassifierRecommenderInitializer;
import de.tudarmstadt.ukp.inception.project.initializers.neannotation.NamedEntityStringRecommenderInitializer;
import de.tudarmstadt.ukp.inception.project.initializers.wikidatalinking.EntityLinkingProjectInitializer;
import de.tudarmstadt.ukp.inception.project.initializers.wikidatalinking.NamedEntityIdentifierStringRecommenderInitializer;
import de.tudarmstadt.ukp.inception.project.initializers.wikidatalinking.ProfileBasedKnowledgeBaseInitializer;
import de.tudarmstadt.ukp.inception.project.initializers.wikidatalinking.WikiDataKnowledgeBaseInitializer;
import de.tudarmstadt.ukp.inception.recommendation.api.RecommendationService;
import de.tudarmstadt.ukp.inception.recommendation.config.RecommenderServiceAutoConfiguration;
import de.tudarmstadt.ukp.inception.recommendation.imls.opennlp.ner.OpenNlpNerRecommenderFactory;
import de.tudarmstadt.ukp.inception.recommendation.imls.stringmatch.config.StringMatchingRecommenderAutoConfiguration;
import de.tudarmstadt.ukp.inception.recommendation.imls.stringmatch.span.StringMatchingRecommenderFactory;
import de.tudarmstadt.ukp.inception.schema.api.AnnotationSchemaService;

@AutoConfigureAfter({ //
        KnowledgeBaseServiceAutoConfiguration.class, //
        RecommenderServiceAutoConfiguration.class, //
        StringMatchingRecommenderAutoConfiguration.class })
@Configuration
public class WikiDataLinkingProjectInitializersAutoConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final Map<String, KnowledgeBaseProfile> PROFILES;

    static {
        Map<String, KnowledgeBaseProfile> profiles;
        try {
            profiles = KnowledgeBaseProfile.readKnowledgeBaseProfiles();
        }
        catch (IOException e) {
            LOG.error("Error reading knowledge base profiles, ", e);
            profiles = Collections.emptyMap();
        }

        PROFILES = Collections.unmodifiableMap(profiles);
    }

    @ConditionalOnBean(RecommendationService.class)
    @Bean
    public NamedEntityIdentifierStringRecommenderInitializer namedEntityIdentifierStringRecommenderInitializer(
            RecommendationService aRecommenderService, AnnotationSchemaService aAnnotationService,
            StringMatchingRecommenderFactory aRecommenderFactory)
    {
        return new NamedEntityIdentifierStringRecommenderInitializer(aRecommenderService,
                aAnnotationService, aRecommenderFactory);
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public WikiDataKnowledgeBaseInitializer wikiDataKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        return new WikiDataKnowledgeBaseInitializer(aKbService);
    }

    // This must come after wikiDataKnowledgeBaseInitializer(...)
    @ConditionalOnBean(WikiDataKnowledgeBaseInitializer.class)
    @Bean
    public EntityLinkingProjectInitializer entityLinkingProjectInitializer(
            ApplicationContext aContext, AnnotationSchemaService aAnnotationService)
    {
        return new EntityLinkingProjectInitializer(aContext, aAnnotationService);
    }

    @Bean
    public EntityAnnotationProjectInitializer entityAnnotationProjectInitializer(
            ApplicationContext aContext, AnnotationSchemaService aAnnotationService,
            DocumentService aDocumentService, UserDao aUserService,
            StringFeatureSupport aStringFeatureSupport)
    {
        return new EntityAnnotationProjectInitializer(aContext, aAnnotationService,
                aDocumentService, aUserService, aStringFeatureSupport);
    }

    @ConditionalOnBean(RecommendationService.class)
    @Bean
    public NamedEntityStringRecommenderInitializer namedEntityStringRecommenderInitializer(
            RecommendationService aRecommenderService, AnnotationSchemaService aAnnotationService,
            StringMatchingRecommenderFactory aRecommenderFactory)
    {
        return new NamedEntityStringRecommenderInitializer(aRecommenderService, aAnnotationService,
                aRecommenderFactory);
    }

    @ConditionalOnBean(RecommendationService.class)
    @Bean
    public NamedEntitySequenceClassifierRecommenderInitializer namedEntitySequenceClassifierRecommenderInitializer(
            RecommendationService aRecommenderService, AnnotationSchemaService aAnnotationService,
            OpenNlpNerRecommenderFactory aRecommenderFactory)
    {
        return new NamedEntitySequenceClassifierRecommenderInitializer(aRecommenderService,
                aAnnotationService, aRecommenderFactory);
    }

    @Bean
    public NamedEntitySampleDataTagSetInitializer namedEntitySampleDataTagSetInitializer(
            AnnotationSchemaService aAnnotationSchemaService)
    {
        return new NamedEntitySampleDataTagSetInitializer(aAnnotationSchemaService);
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer agroVocKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "AgriculturalKnowledgeBase.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService, PROFILES.get("agrovoc"),
                thumbnail)
        {
        };
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer dbPediaNetKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "Lexicon.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService, PROFILES.get("db_pedia"),
                thumbnail)
        {
        };
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer dblpKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "GenericKnowledgeBase.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService, PROFILES.get("dblp"), thumbnail)
        {
        };
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer euCorporateBodiesKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "GenericKnowledgeBase.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService,
                PROFILES.get("eu-corporate-bodies"), thumbnail)
        {
        };
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer euroVocKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "GenericKnowledgeBase.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService, PROFILES.get("eurovoc"),
                thumbnail)
        {
        };
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer geneOntologyKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "MedicalKnowledgeBase.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService, PROFILES.get("go"), thumbnail)
        {
        };
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer hpoKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "MedicalKnowledgeBase.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService, PROFILES.get("hpo"), thumbnail)
        {
        };
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer meshKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "MedicalKnowledgeBase.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService, PROFILES.get("mesh"), thumbnail)
        {
        };
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer iaoKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "GenericKnowledgeBase.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService, PROFILES.get("iao"), thumbnail)
        {
        };
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer oliaPennKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "Dictionary.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService, PROFILES.get("olia_penn.owl"),
                thumbnail)
        {
        };
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer snomwedCtKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "MedicalKnowledgeBase.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService, PROFILES.get("snomed-ct"),
                thumbnail)
        {
        };
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer yagoKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "Lexicon.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService, PROFILES.get("yago"), thumbnail)
        {
        };
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer zbwGndKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "GenericKnowledgeBase.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService, PROFILES.get("zbw-gnd"),
                thumbnail)
        {
        };
    }

    @ConditionalOnBean(KnowledgeBaseService.class)
    @Bean
    public ProfileBasedKnowledgeBaseInitializer zbwStwEconomicsKnowledgeBaseInitializer(
            KnowledgeBaseService aKbService)
    {
        var thumbnail = new PackageResourceReference(WikiDataKnowledgeBaseInitializer.class,
                "GenericKnowledgeBase.svg");
        return new ProfileBasedKnowledgeBaseInitializer(aKbService,
                PROFILES.get("zbw-stw-economics"), thumbnail)
        {
        };
    }
}
