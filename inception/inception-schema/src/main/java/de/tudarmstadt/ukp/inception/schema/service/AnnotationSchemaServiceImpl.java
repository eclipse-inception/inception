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
package de.tudarmstadt.ukp.inception.schema.service;

import static de.tudarmstadt.ukp.clarin.webanno.model.LinkMode.WITH_ROLE;
import static de.tudarmstadt.ukp.clarin.webanno.model.MultiValueMode.ARRAY;
import static de.tudarmstadt.ukp.inception.project.api.ProjectService.withProjectLogger;
import static de.tudarmstadt.ukp.inception.schema.api.AttachedAnnotation.Direction.INCOMING;
import static de.tudarmstadt.ukp.inception.schema.api.AttachedAnnotation.Direction.LOOP;
import static de.tudarmstadt.ukp.inception.schema.api.AttachedAnnotation.Direction.OUTGOING;
import static de.tudarmstadt.ukp.inception.support.WebAnnoConst.RESTRICTED_FEATURE_NAMES;
import static de.tudarmstadt.ukp.inception.support.uima.ICasUtil.selectByAddr;
import static de.tudarmstadt.ukp.inception.support.uima.WebAnnoCasUtil.getRealCas;
import static de.tudarmstadt.ukp.inception.support.uima.WebAnnoCasUtil.isNativeUimaType;
import static de.tudarmstadt.ukp.inception.support.uima.WebAnnoCasUtil.isSame;
import static java.lang.String.join;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Comparator.comparing;
import static java.util.Objects.isNull;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.uima.cas.impl.Serialization.deserializeCASComplete;
import static org.apache.uima.cas.impl.Serialization.serializeCASComplete;
import static org.apache.uima.cas.impl.Serialization.serializeWithCompression;
import static org.apache.uima.fit.factory.TypeSystemDescriptionFactory.createTypeSystemDescription;
import static org.apache.uima.fit.util.FSUtil.setFeature;
import static org.apache.uima.util.CasCreationUtils.mergeTypeSystems;
import static org.apache.uima.util.TypeSystemUtil.isFeatureName;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringTokenizer;

import org.apache.uima.UIMAException;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.Feature;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.Type;
import org.apache.uima.cas.TypeSystem;
import org.apache.uima.cas.impl.CASImpl;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.fit.factory.CasFactory;
import org.apache.uima.fit.util.CasUtil;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.metadata.FeatureDescription;
import org.apache.uima.resource.metadata.TypeDescription;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.apache.uima.resource.metadata.impl.TypeSystemDescription_impl;
import org.apache.uima.util.CasCreationUtils;
import org.apache.uima.util.CasIOUtils;
import org.apache.wicket.validation.ValidationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import de.tudarmstadt.ukp.clarin.webanno.api.annotation.util.TypeSystemAnalysis;
import de.tudarmstadt.ukp.clarin.webanno.api.casstorage.CasUpgradeMode;
import de.tudarmstadt.ukp.clarin.webanno.api.casstorage.session.CasStorageSession;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature_;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer_;
import de.tudarmstadt.ukp.clarin.webanno.model.ImmutableTag;
import de.tudarmstadt.ukp.clarin.webanno.model.LinkMode;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.ReorderableTag;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.Tag;
import de.tudarmstadt.ukp.clarin.webanno.model.TagSet;
import de.tudarmstadt.ukp.clarin.webanno.model.Tag_;
import de.tudarmstadt.ukp.dkpro.core.api.metadata.type.TagsetDescription;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import de.tudarmstadt.ukp.inception.annotation.layer.chain.api.ChainAdapter;
import de.tudarmstadt.ukp.inception.annotation.layer.chain.api.ChainLayerSupport;
import de.tudarmstadt.ukp.inception.annotation.layer.relation.api.RelationAdapter;
import de.tudarmstadt.ukp.inception.annotation.layer.relation.api.RelationLayerSupport;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.SpanAdapter;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.SpanLayerSupport;
import de.tudarmstadt.ukp.inception.annotation.storage.CasMetadataUtils;
import de.tudarmstadt.ukp.inception.schema.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.inception.schema.api.AttachedAnnotation;
import de.tudarmstadt.ukp.inception.schema.api.adapter.IllegalFeatureValueException;
import de.tudarmstadt.ukp.inception.schema.api.adapter.TypeAdapter;
import de.tudarmstadt.ukp.inception.schema.api.config.AnnotationSchemaProperties;
import de.tudarmstadt.ukp.inception.schema.api.event.TagCreatedEvent;
import de.tudarmstadt.ukp.inception.schema.api.event.TagDeletedEvent;
import de.tudarmstadt.ukp.inception.schema.api.event.TagUpdatedEvent;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureSupportRegistry;
import de.tudarmstadt.ukp.inception.schema.api.feature.LinkWithRoleModel;
import de.tudarmstadt.ukp.inception.schema.api.layer.LayerSupport;
import de.tudarmstadt.ukp.inception.schema.api.layer.LayerSupportRegistry;
import de.tudarmstadt.ukp.inception.schema.config.AnnotationSchemaServiceAutoConfiguration;
import de.tudarmstadt.ukp.inception.support.uima.ICasUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

/**
 * <p>
 * This class is exposed as a Spring Component via
 * {@link AnnotationSchemaServiceAutoConfiguration#annotationSchemaService}.
 * </p>
 */
public class AnnotationSchemaServiceImpl
    implements AnnotationSchemaService
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final EntityManager entityManager;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final LayerSupportRegistry layerSupportRegistry;
    private final FeatureSupportRegistry featureSupportRegistry;
    private final LoadingCache<TagSet, List<ImmutableTag>> immutableTagsCache;
    private final TypeSystemDescription builtInTypes;
    private final AnnotationSchemaProperties annotationEditorProperties;

    public AnnotationSchemaServiceImpl()
    {
        this(null, null, null, null, null);
    }

    public AnnotationSchemaServiceImpl(LayerSupportRegistry aLayerSupportRegistry,
            FeatureSupportRegistry aFeatureSupportRegistry,
            ApplicationEventPublisher aApplicationEventPublisher,
            AnnotationSchemaProperties aAnnotationEditorProperties, EntityManager aEntityManager)
    {
        layerSupportRegistry = aLayerSupportRegistry;
        featureSupportRegistry = aFeatureSupportRegistry;
        applicationEventPublisher = aApplicationEventPublisher;
        annotationEditorProperties = aAnnotationEditorProperties;
        entityManager = aEntityManager;

        immutableTagsCache = Caffeine.newBuilder() //
                .expireAfterAccess(5, MINUTES) //
                .maximumSize(10 * 1024) //
                .build(this::loadImmutableTags);

        try {
            builtInTypes = createTypeSystemDescription();
        }
        catch (ResourceInitializationException e) {
            throw new IllegalStateException("Unable to initialize built-in type system", e);
        }
    }

    @Override
    @Transactional
    public void createTag(Tag aTag)
    {
        var created = createTagNoLog(aTag);

        flushImmutableTagCache(aTag.getTagSet());

        try (var logCtx = withProjectLogger(aTag.getTagSet().getProject())) {
            LOG.info("{} tag [{}]({}) in tagset {} in project {}", created ? "Created" : "Updated",
                    aTag.getName(), aTag.getId(), aTag.getTagSet(), aTag.getTagSet().getProject());
        }
    }

    @Override
    @Transactional
    public void createTags(Tag... aTags)
    {
        if (aTags == null || aTags.length == 0) {
            return;
        }

        var tagset = aTags[0].getTagSet();
        var project = tagset.getProject();

        var createdCount = 0;
        var updatedCount = 0;
        for (var tag : aTags) {
            var created = createTagNoLog(tag);
            if (created) {
                createdCount++;
            }
            else {
                updatedCount++;
            }
        }

        try (var logCtx = withProjectLogger(project)) {
            LOG.info("Created {} tags and updated {} tags in tagset {} in project {}", createdCount,
                    updatedCount, tagset, project);
        }

        flushImmutableTagCache(tagset);
    }

    private boolean createTagNoLog(Tag aTag)
    {
        if (isNull(aTag.getId())) {
            entityManager.persist(aTag);

            if (applicationEventPublisher != null) {
                applicationEventPublisher.publishEvent(new TagCreatedEvent(this, aTag));
            }

            return true;
        }
        else {
            entityManager.merge(aTag);

            if (applicationEventPublisher != null) {
                applicationEventPublisher.publishEvent(new TagUpdatedEvent(this, aTag));
            }

            return false;
        }
    }

    @Override
    @Transactional
    public void updateTagRanks(TagSet aTagSet, List<Tag> aTags)
    {
        var dbTags = new HashMap<Tag, Tag>();
        for (var t : listTags(aTagSet)) {
            dbTags.put(t, t);
        }

        int n = 0;
        for (var tag : aTags) {
            if (!aTagSet.equals(tag.getTagSet())) {
                throw new IllegalArgumentException("All tags to be updated must belong to "
                        + aTagSet + ", but [" + tag + "] belongs to " + tag.getTagSet());
            }

            var dbTag = dbTags.get(tag);
            if (dbTag != null) {
                dbTag.setRank(n);
                tag.setRank(n);
                n++;
            }
        }
    }

    @Override
    @Transactional
    public void updateFeatureRanks(AnnotationLayer aLayer, List<AnnotationFeature> aFeatures)
    {
        var dbFeatures = new HashMap<AnnotationFeature, AnnotationFeature>();
        for (var t : listAnnotationFeature(aLayer)) {
            dbFeatures.put(t, t);
        }

        int n = 0;
        for (var feature : aFeatures) {
            if (!aLayer.equals(feature.getLayer())) {
                throw new IllegalArgumentException("All features to be updated must belong to "
                        + aLayer + ", but " + feature + " belongs to " + feature.getLayer());
            }

            var dbFeature = dbFeatures.get(feature);
            if (dbFeature != null) {
                dbFeature.setRank(n);
                feature.setRank(n);
                n++;
            }
        }
    }

    @Override
    @Transactional
    public void createTagSet(TagSet aTagSet)
    {
        try (var logCtx = withProjectLogger(aTagSet.getProject())) {
            if (isNull(aTagSet.getId())) {
                entityManager.persist(aTagSet);
                LOG.info("Created tagset {} in project {}", aTagSet, aTagSet.getProject());
            }
            else {
                entityManager.merge(aTagSet);
                LOG.info("Updated tagset {} in project {}", aTagSet, aTagSet.getProject());
            }
        }
    }

    @Override
    @Transactional
    public void createOrUpdateLayer(AnnotationLayer aLayer)
    {
        try (var logCtx = withProjectLogger(aLayer.getProject())) {
            if (isNull(aLayer.getId())) {
                entityManager.persist(aLayer);
                LOG.info("Created layer {} in project {}", aLayer, aLayer.getProject());
            }
            else {
                entityManager.merge(aLayer);
                LOG.info("Updated layer {} in project {}", aLayer, aLayer.getProject());
            }
        }
    }

    @Override
    @Transactional
    public void createFeature(AnnotationFeature aFeature)
    {
        try (var logCtx = withProjectLogger(aFeature.getProject())) {
            if (isNull(aFeature.getId())) {
                entityManager.persist(aFeature);
                LOG.info("Created feature {} in project {}", aFeature, aFeature.getProject());
            }
            else {
                entityManager.merge(aFeature);
                LOG.info("Updated feature {} in project {}", aFeature, aFeature.getProject());
            }
        }
    }

    @Override
    @Transactional
    public Optional<Tag> getTag(long aId)
    {
        return Optional.ofNullable(entityManager.find(Tag.class, aId));
    }

    @Override
    @Transactional
    public Optional<Tag> getTag(String aTagName, TagSet aTagSet)
    {
        try {
            return Optional.of(entityManager
                    .createQuery("FROM Tag WHERE name = :name AND tagSet = :tagSet", Tag.class)
                    .setParameter("name", aTagName) //
                    .setParameter("tagSet", aTagSet) //
                    .getSingleResult());
        }
        catch (NoResultException e) {
            return Optional.empty();
        }
    }

    /**
     * @deprecated Use {@code getTag(name, tagset).isPresent()}.
     */
    @Deprecated
    @Override
    public boolean existsTag(String aTagName, TagSet aTagSet)
    {
        return getTag(aTagName, aTagSet).isPresent();
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class, readOnly = true)
    public boolean existsTagSet(String aName, Project aProject)
    {
        try {
            entityManager
                    .createQuery("FROM TagSet WHERE name = :name AND project = :project",
                            TagSet.class)
                    .setParameter("name", aName).setParameter("project", aProject)
                    .getSingleResult();
            return true;
        }
        catch (NoResultException e) {
            return false;

        }
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class, readOnly = true)
    public boolean existsTagSet(Project aProject)
    {
        try {
            entityManager.createQuery("FROM TagSet WHERE  project = :project", TagSet.class)
                    .setParameter("project", aProject).getSingleResult();
            return true;
        }
        catch (NoResultException e) {
            return false;

        }
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class, readOnly = true)
    public boolean existsLayer(Project aProject)
    {
        return entityManager.createQuery(
                "SELECT COUNT(*) FROM AnnotationLayer WHERE project = :project AND name NOT IN (:excluded)",
                Long.class) //
                .setParameter("project", aProject) //
                .setParameter("excluded", asList(Token._TypeName, Sentence._TypeName)) //
                .getSingleResult() > 0;
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class, readOnly = true)
    public boolean existsLayer(String aName, Project aProject)
    {
        try {
            entityManager
                    .createQuery("FROM AnnotationLayer WHERE name = :name AND project = :project",
                            AnnotationLayer.class)
                    .setParameter("name", aName) //
                    .setParameter("project", aProject) //
                    .getSingleResult();
            return true;
        }
        catch (NoResultException e) {
            return false;
        }
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class, readOnly = true)
    public boolean existsLayerWithShortName(String aShortName, Project aProject)
    {
        try {
            entityManager.createQuery(
                    "FROM AnnotationLayer WHERE shortName = :shortName AND project = :project",
                    AnnotationLayer.class).setParameter("shortName", aShortName) //
                    .setParameter("project", aProject) //
                    .getSingleResult();
            return true;
        }
        catch (NoResultException e) {
            return false;
        }
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class, readOnly = true)
    public boolean existsLayer(String aName, String aType, Project aProject)
    {
        try {
            entityManager.createQuery(
                    "FROM AnnotationLayer WHERE name = :name AND type = :type AND project = :project",
                    AnnotationLayer.class) //
                    .setParameter("name", aName) //
                    .setParameter("type", aType) //
                    .setParameter("project", aProject) //
                    .getSingleResult();
            return true;
        }
        catch (NoResultException e) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsEnabledLayerOfType(Project aProject, String aType)
    {
        var query = join("\n", //
                "FROM AnnotationLayer", //
                "WHERE project = :project ", //
                "AND type = :type", //
                "AND enabled = true");

        var layers = entityManager.createQuery(query, AnnotationLayer.class) //
                .setParameter("project", aProject) //
                .setParameter("type", aType) //
                .getResultList();

        return layers.stream().anyMatch(layer -> {
            try {
                layerSupportRegistry.getLayerSupport(layer);
                return true;
            }
            catch (IllegalArgumentException e) {
                return false;
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsFeature(String aName, AnnotationLayer aLayer)
    {
        try {
            entityManager
                    .createQuery("FROM AnnotationFeature WHERE name = :name AND layer = :layer",
                            AnnotationFeature.class)
                    .setParameter("name", aName) //
                    .setParameter("layer", aLayer) //
                    .getSingleResult();
            return true;
        }
        catch (NoResultException e) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsEnabledFeatureOfType(Project aProject, String aType)
    {
        var query = join("\n", //
                "FROM AnnotationFeature", //
                "WHERE project = :project ", //
                "AND type = :type", //
                "AND enabled = true", //
                "AND layer.enabled = true");

        var features = entityManager.createQuery(query, AnnotationFeature.class) //
                .setParameter("project", aProject) //
                .setParameter("type", aType) //
                .getResultList();

        return features.stream().anyMatch(feature -> {
            try {
                featureSupportRegistry.findExtension(feature);
                return true;
            }
            catch (IllegalArgumentException e) {
                return false;
            }
        });
    }

    @Override
    @Transactional
    public TagSet getTagSet(String aName, Project aProject)
    {
        return entityManager
                .createQuery("FROM TagSet WHERE name = :name AND project =:project", TagSet.class)
                .setParameter("name", aName) //
                .setParameter("project", aProject) //
                .getSingleResult();
    }

    @Override
    @Transactional
    public TagSet getTagSet(long aId)
    {
        return entityManager.find(TagSet.class, aId);
    }

    @Override
    @Transactional
    public AnnotationLayer getLayer(long aId)
    {
        return entityManager.find(AnnotationLayer.class, aId);
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public Optional<AnnotationLayer> getLayer(Project aProject, long aLayerId)
    {
        var layer = getLayer(aLayerId);

        // Check that the layer actually belongs to the project
        if (layer != null && !Objects.equals(layer.getProject().getId(), aProject.getId())) {
            return Optional.empty();
        }

        return Optional.ofNullable(layer);
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public AnnotationLayer findLayer(Project aProject, String aName)
    {
        // If there is a layer definition for the given name, then return it immediately
        Optional<AnnotationLayer> layer = getLayerInternal(aName, aProject);
        if (layer.isPresent()) {
            return layer.get();
        }

        TypeSystemDescription tsd;
        try {
            tsd = getFullProjectTypeSystem(aProject);
        }
        catch (ResourceInitializationException e) {
            throw new RuntimeException(e);
        }

        TypeDescription type = tsd.getType(aName);
        // If the super-type is not covered by the type system, then it is most likely a
        // UIMA built-in type. In this case we can stop the search since we do not have
        // layer definitions for UIMA built-in types.
        if (type == null) {
            throw new NoResultException("Type [" + aName + "] not found in the type system");
        }

        // If there is no layer definition for the given type name, try using the type system
        // definition to determine a suitable layer definition for a super type of the given type.
        while (true) {
            // If there is no super type, then we cannot find a suitable layer definition
            if (type.getSupertypeName() == null) {
                throw new NoResultException(
                        "No more super-types - no suitable layer definition found for type ["
                                + aName + "]");
            }

            // If there is a super-type then see if there is layer definition for it
            var superType = tsd.getType(type.getSupertypeName());

            // If the super-type is not covered by the type system, then it is most likely a
            // UIMA built-in type. In this case we can stop the search since we do not have
            // layer definitions for UIMA built-in types.
            if (superType == null) {
                throw new NoResultException("Super-type [" + type.getSupertypeName() + "] of type ["
                        + aName + "] does not correspond to any project layer");
            }

            layer = getLayerInternal(superType.getName(), aProject);

            // If the a layer definition of the given type was found, return it
            if (layer.isPresent()) {
                return layer.get();
            }

            // Otherwise attempt going one level higher in the inheritance hierarchy
        }
    }

    private Optional<AnnotationLayer> getLayerInternal(String aName, Project aProject)
    {
        String query = join("\n", //
                "FROM AnnotationLayer ", //
                "WHERE name = :name AND project = :project");

        return entityManager.createQuery(query, AnnotationLayer.class) //
                .setParameter("name", aName) //
                .setParameter("project", aProject) //
                .getResultStream() //
                .findFirst();
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public AnnotationLayer findLayer(Project aProject, FeatureStructure aFS)
    {
        String layerName = aFS.getType().getName();
        AnnotationLayer layer;
        try {
            layer = findLayer(aProject, layerName);
        }
        catch (NoResultException e) {
            if (layerName.endsWith(ChainAdapter.CHAIN)) {
                layerName = layerName.substring(0,
                        layerName.length() - ChainAdapter.CHAIN.length());
            }
            if (layerName.endsWith(ChainAdapter.LINK)) {
                layerName = layerName.substring(0, layerName.length() - ChainAdapter.LINK.length());
            }
            layer = findLayer(aProject, layerName);
        }

        return layer;
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public AnnotationFeature getFeature(long aId)
    {
        return entityManager
                .createQuery("From AnnotationFeature where id = :id", AnnotationFeature.class)
                .setParameter("id", aId).getSingleResult();
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public AnnotationFeature getFeature(String aName, AnnotationLayer aLayer)
    {
        return entityManager
                .createQuery("From AnnotationFeature where name = :name AND layer = :layer",
                        AnnotationFeature.class)
                .setParameter("name", aName) //
                .setParameter("layer", aLayer) //
                .getSingleResult();
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public Optional<AnnotationFeature> getFeature(long aLayerId, String aName)
    {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(AnnotationFeature.class);
        var feature = query.from(AnnotationFeature.class);

        var namePredicate = cb.equal(feature.get(AnnotationFeature_.name), aName);
        var layerPredicate = cb
                .equal(feature.get(AnnotationFeature_.layer).get(AnnotationLayer_.id), aLayerId);

        query.select(feature).where(cb.and(namePredicate, layerPredicate));

        return entityManager.createQuery(query) //
                .getResultStream() //
                .findFirst();
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class, readOnly = true)
    public boolean existsType(String aName, String aType)
    {
        try {
            entityManager
                    .createQuery("From AnnotationLayer where name = :name AND type = :type",
                            AnnotationLayer.class)
                    .setParameter("name", aName).setParameter("type", aType).getSingleResult();
            return true;
        }
        catch (NoResultException e) {
            return false;
        }
    }

    @Override
    @Transactional
    public TagSet createTagSet(String aDescription, String aTagSetName, String aLanguage,
            String[] aTags, String[] aTagDescription, Project aProject)
        throws IOException
    {
        TagSet tagSet = new TagSet();
        tagSet.setDescription(aDescription);
        tagSet.setLanguage(aLanguage);
        tagSet.setName(aTagSetName);
        tagSet.setProject(aProject);

        createTagSet(tagSet);

        int createdCount = 0;
        int updatedCount = 0;
        int i = 0;
        for (String tagName : aTags) {
            Tag tag = new Tag();
            tag.setTagSet(tagSet);
            tag.setDescription(aTagDescription[i]);
            tag.setName(tagName);
            boolean created = createTagNoLog(tag);
            if (created) {
                createdCount++;
            }
            else {
                updatedCount++;
            }
            i++;
        }

        try (var logCtx = withProjectLogger(aProject)) {
            LOG.info("Created {} tags and updated {} tags in tagset {} in project {}", createdCount,
                    updatedCount, tagSet, aProject);
        }

        return tagSet;
    }

    @Override
    @Transactional
    public List<AnnotationLayer> listAnnotationLayer(Project aProject)
    {
        String query = join("\n", //
                "FROM AnnotationLayer", //
                "WHERE project = :project ", //
                "ORDER BY uiName");

        return entityManager.createQuery(query, AnnotationLayer.class)
                .setParameter("project", aProject) //
                .getResultList();
    }

    @Override
    @Transactional
    public List<AnnotationLayer> listAttachedRelationLayers(AnnotationLayer aLayer)
    {
        Objects.requireNonNull(aLayer, "Parameter [layer] must be specified");

        if (!SpanLayerSupport.TYPE.equals(aLayer.getType())) {
            return emptyList();
        }

        var cb = entityManager.getCriteriaBuilder();
        var cq = cb.createQuery(AnnotationLayer.class);

        var layer = cq.from(AnnotationLayer.class);
        var feature = layer.join(AnnotationLayer_.attachFeature, JoinType.LEFT);

        var predicates = new ArrayList<Predicate>();
        predicates.add(cb.equal(layer.get(AnnotationLayer_.type), RelationLayerSupport.TYPE));
        predicates.add(cb.equal(layer.get(AnnotationLayer_.project), aLayer.getProject()));
        var attachTypeCondition = cb.or( //
                cb.equal(layer.get(AnnotationLayer_.attachType), aLayer), //
                cb.isNull(layer.get(AnnotationLayer_.attachType)), //
                cb.equal(feature.get(AnnotationFeature_.type), aLayer.getName()));
        predicates.add(attachTypeCondition);

        cq.where(predicates.toArray(new Predicate[0]));
        cq.orderBy(cb.asc(layer.get(AnnotationLayer_.uiName)));

        return entityManager.createQuery(cq).getResultList();

        // var query = String.join("\n",
        // "SELECT l FROM AnnotationLayer l LEFT JOIN l.attachFeature f ", //
        // "WHERE l.type = :type AND ", //
        // " l.project = :project AND ", //
        // " (l.attachType = :attachType OR l.attachType IS NULL OR f.type = :attachTypeName) ", //
        // "ORDER BY l.uiName");
        //
        // return entityManager.createQuery(query, AnnotationLayer.class)
        // .setParameter("type", RelationLayerSupport.TYPE) //
        // .setParameter("attachType", aLayer) //
        // .setParameter("attachTypeName", aLayer.getName())
        // // Checking for project is necessary because type match is string-based
        // .setParameter("project", aLayer.getProject()) //
        // .getResultList();
    }

    @Transactional
    List<AnnotationFeature> listAttachingFeatures(AnnotationLayer aLayer)
    {
        String query = join("\n", //
                "FROM AnnotationFeature ", //
                "WHERE type    = :type AND", //
                "      project = :project", //
                "ORDER BY rank ASC, uiName ASC");

        // This should not be cached because we do not have a proper foreign key relation to
        // the type.
        return entityManager.createQuery(query, AnnotationFeature.class)
                .setParameter("type", aLayer.getName()) //
                // Checking for project is necessary because type match is string-based
                .setParameter("project", aLayer.getProject()) //
                .getResultList();
    }

    @Override
    @Transactional
    public List<AnnotationFeature> listAttachedLinkFeatures(AnnotationLayer aLayer)
    {
        String query = join("\n", //
                "FROM AnnotationFeature", //
                "WHERE linkMode in (:modes) AND ", //
                "      project = :project AND ", //
                "      type in (:attachType) ", //
                "ORDER BY rank ASC, uiName ASC");

        return entityManager.createQuery(query, AnnotationFeature.class)
                .setParameter("modes", asList(LinkMode.SIMPLE, LinkMode.WITH_ROLE))
                .setParameter("attachType", asList(aLayer.getName(), CAS.TYPE_NAME_ANNOTATION))
                // Checking for project is necessary because type match is string-based
                .setParameter("project", aLayer.getProject()) //
                .getResultList();
    }

    @Override
    @Transactional
    public List<AnnotationFeature> listAttachedSpanFeatures(AnnotationLayer aLayer)
    {
        String query = join("\n", //
                "SELECT l.attachFeature", //
                "FROM AnnotationLayer AS l", //
                "WHERE l.attachType = :layer", //
                "ORDER BY l.attachFeature.rank ASC, l.attachFeature.uiName ASC");

        return entityManager.createQuery(query, AnnotationFeature.class)
                .setParameter("layer", aLayer) //
                .getResultList();
    }

    @Override
    @Transactional
    public List<AnnotationFeature> listAnnotationFeature(AnnotationLayer aLayer)
    {
        if (isNull(aLayer) || isNull(aLayer.getId())) {
            return new ArrayList<>();
        }

        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(AnnotationFeature.class);
        var root = query.from(AnnotationFeature.class);

        query //
                .where(cb.equal(root.get(AnnotationFeature_.layer), aLayer))
                .orderBy(cb.asc(root.get(AnnotationFeature_.rank)),
                        cb.asc(root.get(AnnotationFeature_.uiName)));

        return entityManager.createQuery(query).getResultList();
    }

    @Override
    @Transactional
    public List<AnnotationFeature> listEnabledFeatures(AnnotationLayer aLayer)
    {
        return listAnnotationFeature(aLayer).stream() //
                .filter(AnnotationFeature::isEnabled) //
                .filter(featureSupportRegistry::isAccessible) //
                .toList();
    }

    @Override
    @Transactional
    public List<AnnotationFeature> listAnnotationFeature(Project aProject)
    {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(AnnotationFeature.class);
        var root = query.from(AnnotationFeature.class);

        query //
                .where(cb.equal(root.get(AnnotationFeature_.project), aProject)) //
                .orderBy(cb.asc(root.get(AnnotationFeature_.layer).get(AnnotationLayer_.uiName)),
                        cb.asc(root.get(AnnotationFeature_.rank)),
                        cb.asc(root.get(AnnotationFeature_.uiName)));

        return entityManager.createQuery(query) //
                .getResultList();
    }

    @Override
    @Transactional
    public List<AnnotationFeature> listEnabledFeatures(Project aProject)
    {
        return listAnnotationFeature(aProject).stream() //
                .filter(f -> f.getLayer().isEnabled()) //
                .filter(AnnotationFeature::isEnabled) //
                .filter(featureSupportRegistry::isAccessible) //
                .toList();
    }

    @Override
    @Transactional
    public List<Tag> listTags(TagSet aTagSet)
    {
        if (aTagSet == null) {
            return emptyList();
        }

        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(Tag.class);
        var root = query.from(Tag.class);

        query //
                .where(cb.equal(root.get(Tag_.tagSet), aTagSet)) //
                .orderBy(cb.asc(root.get(Tag_.rank)), //
                        cb.asc(root.get(Tag_.name)));

        return entityManager.createQuery(query).getResultList();
    }

    private List<ImmutableTag> loadImmutableTags(TagSet aTagSet)
    {
        return listTags(aTagSet).stream() //
                .map(ImmutableTag::new) //
                .collect(toList());
    }

    private void flushImmutableTagCache(TagSet aTagSet)
    {
        immutableTagsCache.asMap().keySet()
                .removeIf(key -> Objects.equals(key.getId(), aTagSet.getId()));
    }

    @Override
    public List<ImmutableTag> listTagsImmutable(TagSet aTagSet)
    {
        if (aTagSet == null) {
            return emptyList();
        }

        return immutableTagsCache.get(aTagSet);
    }

    @Override
    public List<ReorderableTag> listTagsReorderable(TagSet aTagSet)
    {
        return listTagsImmutable(aTagSet).stream() //
                .map(ReorderableTag::new) //
                .collect(toList());
    }

    @Override
    @Transactional
    public List<TagSet> listTagSets()
    {
        return entityManager //
                .createQuery("FROM TagSet ORDER BY name ASC", TagSet.class).getResultList();
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class)
    public List<TagSet> listTagSets(Project aProject)
    {
        return entityManager
                .createQuery("FROM TagSet where project = :project ORDER BY name ASC", TagSet.class)
                .setParameter("project", aProject).getResultList();
    }

    @Override
    @Transactional
    public void removeTag(Tag aTag)
    {
        entityManager.remove(entityManager.contains(aTag) ? aTag : entityManager.merge(aTag));

        flushImmutableTagCache(aTag.getTagSet());

        if (applicationEventPublisher != null) {
            applicationEventPublisher.publishEvent(new TagDeletedEvent(this, aTag));
        }
    }

    @Override
    @Transactional
    public void removeTagSet(TagSet aTagSet)
    {
        // FIXME: Optimally, this should be cascade-on-delete in the DB
        for (Tag tag : listTags(aTagSet)) {
            entityManager.remove(tag);
        }

        flushImmutableTagCache(aTagSet);

        entityManager
                .remove(entityManager.contains(aTagSet) ? aTagSet : entityManager.merge(aTagSet));
    }

    @Override
    @Transactional
    public void removeFeature(AnnotationFeature aFeature)
    {
        try (var logCtx = withProjectLogger(aFeature.getProject())) {
            entityManager.remove(
                    entityManager.contains(aFeature) ? aFeature : entityManager.merge(aFeature));

            LOG.info("Removed feature {} from project {}", aFeature, aFeature.getProject());
        }
    }

    @Override
    @Transactional
    public void removeLayer(AnnotationLayer aLayer)
    {
        try (var logCtx = withProjectLogger(aLayer.getProject())) {
            var layer = entityManager.contains(aLayer) ? aLayer : entityManager.merge(aLayer);

            // We must not rely on the DB-level CASCADE ON DELETE if Hibernate 2nd-level caching is
            // enabled because if we do, then Hibernate will not know that entries have gone from
            // the DB, will still try to re-hydrate them from the cache and will fail filling in
            // gaps from the DB. So we delete explicitly through Hibernate
            listAnnotationFeature(aLayer).forEach(this::removeFeature);

            // Remove all features in other layers that connect to the layer to be removed. This is
            // necessary so Hibernate cache knows they are gone and also because the relation is
            // modeled by name, so we couldn't use a DB-level CASCADE ON DELETE anyway.
            // It is also necessary to that e.g. the "pos" feature in the built-in "Token" layer
            // gets cleaned up - which the user could never do manually.
            listAttachingFeatures(aLayer).forEach(this::removeFeature);

            entityManager.remove(layer);

            LOG.info("Removed layer {} from project {}", aLayer, aLayer.getProject());
        }
    }

    @Override
    @Transactional
    public void removeAllTags(TagSet aTagSet)
    {
        for (var tag : listTags(aTagSet)) {
            entityManager.remove(tag);
        }

        flushImmutableTagCache(aTagSet);
    }

    @Override
    @Transactional
    public List<AnnotationLayer> listSupportedLayers(Project aProject)
    {
        return listAnnotationLayer(aProject).stream() //
                .filter(layerSupportRegistry::isSupported) //
                .toList();
    }

    @Override
    @Transactional
    public List<AnnotationLayer> listEnabledLayers(Project aProject)
    {
        return listAnnotationLayer(aProject).stream() //
                .filter(AnnotationLayer::isEnabled) //
                .filter(layerSupportRegistry::isSupported) //
                .toList();
    }

    @Override
    @Transactional
    public List<AnnotationFeature> listSupportedFeatures(Project aProject)
    {
        return listAnnotationFeature(aProject).stream() //
                .filter($ -> featureSupportRegistry.findExtension($).isPresent()) //
                .toList();
    }

    @Override
    @Transactional
    public List<AnnotationFeature> listSupportedFeatures(AnnotationLayer aLayer)
    {
        return listAnnotationFeature(aLayer).stream() //
                .filter(featureSupportRegistry::isSupported) //
                .toList();
    }

    @Override
    public TypeSystemDescription getCustomProjectTypes(Project aProject)
    {
        // Create a new type system from scratch
        var tsd = new TypeSystemDescription_impl();

        var allFeaturesInProject = listSupportedFeatures(aProject);

        listSupportedLayers(aProject).stream() //
                .filter(layer -> !layer.isBuiltIn()) //
                .forEachOrdered(layer -> layerSupportRegistry.getLayerSupport(layer)
                        .generateTypes(tsd, layer, allFeaturesInProject));

        return tsd;
    }

    @Override
    public TypeSystemDescription getAllProjectTypes(Project aProject)
        throws ResourceInitializationException
    {
        var allLayersInProject = listSupportedLayers(aProject);
        var allFeaturesInProject = listSupportedFeatures(aProject);

        var allTsds = new ArrayList<TypeSystemDescription>();
        for (var layer : allLayersInProject) {
            LayerSupport<?, ?> layerSupport = layerSupportRegistry.getLayerSupport(layer);

            // for built-in layers, we clone the information from the built-in type descriptors
            var tsd = new TypeSystemDescription_impl();
            if (layer.isBuiltIn()) {
                for (var typeName : layerSupport.getGeneratedTypeNames(layer)) {
                    exportBuiltInTypeDescription(builtInTypes, tsd, typeName);
                }
            }
            // for custom layers, we use the information from the project settings
            else {
                layerSupport.generateTypes(tsd, layer, allFeaturesInProject);
            }
            allTsds.add(tsd);
        }

        {
            // Explicitly add Token because the layer may not be declared in the project
            var tsd = new TypeSystemDescription_impl();
            exportBuiltInTypeDescription(builtInTypes, tsd, Token.class.getName());
            allTsds.add(tsd);
        }

        {
            // Explicitly add Sentence because the layer may not be declared in the project
            var tsd = new TypeSystemDescription_impl();
            exportBuiltInTypeDescription(builtInTypes, tsd, Sentence.class.getName());
            allTsds.add(tsd);
        }

        // The merging action here takes care of removing/conflating potential duplicate
        // declarations
        return CasCreationUtils.mergeTypeSystems(allTsds);
    }

    private void exportBuiltInTypeDescription(TypeSystemDescription aSource,
            TypeSystemDescription aTarget, String aType)
    {
        var builtInType = aSource.getType(aType);

        if (builtInType == null) {
            throw new IllegalArgumentException(
                    "No type description found for type [" + aType + "]");
        }

        var clonedType = aTarget.addType(builtInType.getName(), builtInType.getDescription(),
                builtInType.getSupertypeName());

        if (builtInType.getFeatures() != null) {
            for (var feature : builtInType.getFeatures()) {
                clonedType.addFeature(feature.getName(), feature.getDescription(),
                        feature.getRangeTypeName(), feature.getElementType(),
                        feature.getMultipleReferencesAllowed());

                // Export types referenced by built-in types also as built-in types. Note that
                // it is conceptually impossible for built-in types to refer to custom types, so
                // this is cannot lead to a custom type being exported as a built-in type.
                if (feature.getElementType() != null && !isNativeUimaType(feature.getElementType())
                        && aTarget.getType(feature.getElementType()) == null) {
                    exportBuiltInTypeDescription(aSource, aTarget, feature.getElementType());
                }
                else if (feature.getRangeTypeName() != null
                        && !isNativeUimaType(feature.getRangeTypeName())
                        && aTarget.getType(feature.getRangeTypeName()) == null) {
                    exportBuiltInTypeDescription(aSource, aTarget, feature.getRangeTypeName());
                }
            }
        }
    }

    @Override
    public TypeSystemDescription getFullProjectTypeSystem(Project aProject)
        throws ResourceInitializationException
    {
        return getFullProjectTypeSystem(aProject, true);
    }

    @Override
    public TypeSystemDescription getFullProjectTypeSystem(Project aProject,
            boolean aIncludeInternalTypes)
        throws ResourceInitializationException
    {
        var typeSystems = new ArrayList<TypeSystemDescription>();

        // Types detected by uimaFIT
        typeSystems.add(builtInTypes);

        if (aIncludeInternalTypes) {
            // Types internally used by WebAnno (which we intentionally exclude from being detected
            // by uimaFIT because we want to have an easy way to create a type system excluding
            // these types when we export files from the project
            typeSystems.add(CasMetadataUtils.getInternalTypeSystem());
        }

        // Types declared within the project
        typeSystems.add(getCustomProjectTypes(aProject));

        return mergeTypeSystems(typeSystems);
    }

    @Override
    public void upgradeCas(CAS aCas, AnnotationDocument aAnnotationDocument)
        throws UIMAException, IOException
    {
        upgradeCas(aCas, aAnnotationDocument.getDocument(), aAnnotationDocument.getUser());
    }

    @Override
    public void upgradeCas(CAS aCas, SourceDocument aSourceDocument, String aUser,
            CasUpgradeMode aMode)
        throws UIMAException, IOException
    {
        switch (aMode) {
        case NO_CAS_UPGRADE:
            return;
        case AUTO_CAS_UPGRADE: {
            boolean upgraded = upgradeCasIfRequired(aCas, aSourceDocument);
            if (!upgraded) {
                try (var logCtx = withProjectLogger(aSourceDocument.getProject())) {
                    LOG.debug(
                            "CAS [{}]@{} in {} is already compatible with project type system - skipping upgrade",
                            aUser, aSourceDocument, aSourceDocument.getProject());
                }
            }
            return;
        }
        case FORCE_CAS_UPGRADE:
            upgradeCas(aCas, aSourceDocument, aUser);
            return;
        }
    }

    @Override
    public void upgradeCas(CAS aCas, SourceDocument aSourceDocument, String aUser)
        throws UIMAException, IOException
    {
        try (var logCtx = withProjectLogger(aSourceDocument.getProject())) {
            upgradeCas(aCas, aSourceDocument.getProject());

            LOG.info("Upgraded CAS of user [{}] for document {} in project {}", aUser,
                    aSourceDocument, aSourceDocument.getProject());
        }
    }

    @Override
    public void upgradeCas(CAS aCas, Project aProject) throws UIMAException, IOException
    {
        TypeSystemDescription ts = getFullProjectTypeSystem(aProject);
        upgradeCas(aCas, ts);
    }

    @Override
    public boolean upgradeCasIfRequired(CAS aCas, AnnotationDocument aAnnotationDocument)
        throws UIMAException, IOException
    {
        return upgradeCasIfRequired(asList(aCas), aAnnotationDocument.getDocument().getProject());
    }

    @Override
    public boolean upgradeCasIfRequired(CAS aCas, SourceDocument aSourceDocument)
        throws UIMAException, IOException
    {
        return upgradeCasIfRequired(asList(aCas), aSourceDocument.getProject());
    }

    @Override
    public boolean upgradeCasIfRequired(Iterable<CAS> aCasIter, Project aProject)
        throws UIMAException, IOException
    {
        TypeSystemDescription ts = getFullProjectTypeSystem(aProject);

        // Check if the current CAS already contains the required type system
        boolean upgradePerformed = false;
        nextCas: for (CAS cas : aCasIter) {
            if (cas == null) {
                continue nextCas;
            }

            // When we get here, we must be willing and ready to write to the CAS - even if we
            // eventually figure out that no upgrade is required.
            CasStorageSession.get().assertWritingPermitted(cas);

            if (isUpgradeRequired(cas, ts)) {
                upgradeCas(cas, ts);
                upgradePerformed = true;
            }
        }

        return upgradePerformed;
    }

    @Override
    public void upgradeCas(CAS aCas, TypeSystemDescription aTargetTypeSystem)
        throws UIMAException, IOException
    {
        upgradeCas(aCas, aCas, aTargetTypeSystem);
    }

    /**
     * Load the contents from the source CAS, upgrade it to the target type system and write the
     * results to the target CAS. An in-place upgrade can be achieved by using the same CAS as
     * source and target.
     */
    @Override
    public void upgradeCas(CAS aSourceCas, CAS aTargetCas, TypeSystemDescription aTargetTypeSystem)
        throws UIMAException, IOException
    {
        CasStorageSession.get().assertWritingPermitted(aTargetCas);

        _upgradeCas(aSourceCas, aTargetCas, aTargetTypeSystem);
    }

    // TODO: This method should be come private again ASAP. It is only public to work around
    // the fact that the JSON CAS deserializer does not support lenient deserialization yet!
    public static void _upgradeCas(CAS aSourceCas, CAS aTargetCas,
            TypeSystemDescription aTargetTypeSystem)
        throws IOException, ResourceInitializationException
    {
        // Save source CAS type system (do this early since we might do an in-place upgrade)
        var sourceTypeSystem = aSourceCas.getTypeSystem();

        // Save source CAS contents
        var serializedCasContents = new ByteArrayOutputStream();
        var realSourceCas = getRealCas(aSourceCas);
        // UIMA-6162 Workaround: synchronize CAS during de/serialization
        synchronized (((CASImpl) realSourceCas).getBaseCAS()) {
            // Workaround for https://github.com/apache/uima-uimaj/issues/238
            try (var context = ((CASImpl) realSourceCas).ll_enableV2IdRefs(false)) {
                serializeWithCompression(realSourceCas, serializedCasContents, sourceTypeSystem);
            }
        }

        // Re-initialize the target CAS with new type system
        CAS realTargetCas = getRealCas(aTargetCas);
        // UIMA-6162 Workaround: synchronize CAS during de/serialization
        synchronized (((CASImpl) realTargetCas).getBaseCAS()) {
            var tempCas = CasFactory.createCas(aTargetTypeSystem);
            var serializer = serializeCASComplete((CASImpl) tempCas);
            deserializeCASComplete(serializer, (CASImpl) realTargetCas);

            // Leniently load the source CAS contents into the target CAS
            CasIOUtils.load(new ByteArrayInputStream(serializedCasContents.toByteArray()),
                    getRealCas(aTargetCas), sourceTypeSystem);
        }
    }

    /**
     * Check if the current CAS already contains the required type system.
     */
    private boolean isUpgradeRequired(CAS aCas, TypeSystemDescription aTargetTypeSystem)
    {
        TypeSystem ts = aCas.getTypeSystem();
        boolean upgradeRequired = false;
        nextType: for (TypeDescription tdesc : aTargetTypeSystem.getTypes()) {
            Type t = ts.getType(tdesc.getName());

            // Type does not exist
            if (t == null) {
                LOG.debug("CAS update required: type {} does not exist", tdesc.getName());
                upgradeRequired = true;
                break nextType;
            }

            // Super-type does not match
            if (!Objects.equals(tdesc.getSupertypeName(), ts.getParent(t).getName())) {
                LOG.debug("CAS update required: supertypes of {} do not match: {} <-> {}",
                        tdesc.getName(), tdesc.getSupertypeName(), ts.getParent(t).getName());
                upgradeRequired = true;
                break nextType;
            }

            // Check features
            for (FeatureDescription fdesc : tdesc.getFeatures()) {
                Feature f = t.getFeatureByBaseName(fdesc.getName());

                // Feature does not exist
                if (f == null) {
                    LOG.debug("CAS update required: feature {} on type {} does not exist",
                            fdesc.getName(), tdesc.getName());
                    upgradeRequired = true;
                    break nextType;
                }

                // Range does not match
                if (CAS.TYPE_NAME_FS_ARRAY.equals(fdesc.getRangeTypeName())) {
                    if (!Objects.equals(fdesc.getElementType(),
                            f.getRange().getComponentType().getName())) {
                        LOG.debug(
                                "CAS update required: ranges of feature {} on type {} do not match: {} <-> {}",
                                fdesc.getName(), tdesc.getName(), fdesc.getRangeTypeName(),
                                f.getRange().getName());
                        upgradeRequired = true;
                        break nextType;
                    }
                }
                else {
                    if (!Objects.equals(fdesc.getRangeTypeName(), f.getRange().getName())) {
                        LOG.debug(
                                "CAS update required: ranges of feature {} on type {} do not match: {} <-> {}",
                                fdesc.getName(), tdesc.getName(), fdesc.getRangeTypeName(),
                                f.getRange().getName());
                        upgradeRequired = true;
                        break nextType;
                    }
                }
            }
        }

        return upgradeRequired;
    }

    // NOTE: Using @Transactional here would significantly slow down things because getAdapter() is
    // called rather often. It looks like listSupportedFeatures() works reasonably good also when
    // not called within a transaction. Should it turn out that we would need a @Transactional here,
    // then this should be refactored in some way. E.g. we keep the list of all project layers
    // in the AnnotatorState now - maybe we can use it from there when calling relevant methods
    // on the adapter.
    @Override
    public TypeAdapter getAdapter(AnnotationLayer aLayer)
    {
        return layerSupportRegistry.getLayerSupport(aLayer) //
                .createAdapter(aLayer, () -> listSupportedFeatures(aLayer));
    }

    @Override
    @Transactional(noRollbackFor = NoResultException.class, readOnly = true)
    public TypeAdapter findAdapter(Project aProject, FeatureStructure aFS)
    {
        var layer = findLayer(aProject, aFS);
        return getAdapter(layer);
    }

    @Override
    @Transactional
    public void importUimaTypeSystem(Project aProject, TypeSystemDescription aTSD)
        throws ResourceInitializationException
    {
        var analysis = TypeSystemAnalysis.of(aTSD);
        for (var layer : analysis.getLayers()) {
            // Modifications/imports of built-in layers are not supported
            if (builtInTypes.getType(layer.getName()) != null) {
                continue;
            }

            // If a custom layer does not exist yet, create it
            if (!existsLayer(layer.getName(), aProject)) {
                layer.setProject(aProject);

                // Need to set the attach type
                if (RelationLayerSupport.TYPE.equals(layer.getType())) {
                    var attachLayer = findOrCreateAttachLayer(analysis, layer);
                    layer.setAttachType(attachLayer);
                }

                createOrUpdateLayer(layer);
            }

            // Import the features for the layer except if the layer is a built-in layer.
            // We must not touch the built-in layers because WebAnno may rely on their
            // structure. This is a conservative measure for now any may be relaxed in the
            // future.
            var persistedLayer = findLayer(aProject, layer.getName());
            if (!persistedLayer.isBuiltIn()) {
                for (var f : analysis.getFeatures(layer.getName())) {
                    if (!existsFeature(f.getName(), persistedLayer)) {
                        f.setProject(aProject);
                        f.setLayer(persistedLayer);
                        createFeature(f);
                    }
                }
            }
        }
    }

    private AnnotationLayer findOrCreateAttachLayer(TypeSystemAnalysis aAnalysis,
            AnnotationLayer aLayer)
    {
        var aProject = aLayer.getProject();

        var relDetails = aAnalysis.getRelationDetails(aLayer.getName());
        try {
            // First check if this type is already in the project
            return findLayer(aProject, relDetails.getAttachLayer());
        }
        catch (NoResultException e) {
            // If it does not exist in the project yet, then we create it
            var maybeAttachLayer = aAnalysis.getLayer(relDetails.getAttachLayer());

            if (maybeAttachLayer.isEmpty()) {
                return null;
            }

            var attachLayer = maybeAttachLayer.get();
            attachLayer.setProject(aProject);
            createOrUpdateLayer(attachLayer);
            return attachLayer;
        }
    }

    @Override
    @Transactional
    public List<AttachedAnnotation> getAttachedRels(AnnotationLayer aLayer, AnnotationFS aFs)
    {
        var cas = aFs.getCAS();
        var result = new ArrayList<AttachedAnnotation>();
        nextLayer: for (var layer : listAttachedRelationLayers(aLayer)) {
            var adapter = (RelationAdapter) getAdapter(layer);
            var maybeType = adapter.getAnnotationType(cas);
            if (maybeType.isEmpty()) {
                continue nextLayer;
            }

            var sourceFeature = adapter.getSourceFeature(cas);
            var targetFeature = adapter.getTargetFeature(cas);

            // This code is already prepared for the day that relations can go between
            // different layers and may have different attach features for the source and
            // target layers.
            Feature sourceAttachFeature = null;
            Feature targetAttachFeature = null;
            if (adapter.getAttachFeatureName() != null) {
                sourceAttachFeature = sourceFeature.getRange()
                        .getFeatureByBaseName(adapter.getAttachFeatureName());
                targetAttachFeature = targetFeature.getRange()
                        .getFeatureByBaseName(adapter.getAttachFeatureName());
            }

            for (var relationFS : cas.<Annotation> select(maybeType.get())) {
                if (!(relationFS instanceof AnnotationFS)) {
                    continue;
                }

                // Here we get the annotations that the relation is pointing to in the UI
                AnnotationFS sourceFS;
                if (sourceAttachFeature != null) {
                    sourceFS = (AnnotationFS) relationFS.getFeatureValue(sourceFeature)
                            .getFeatureValue(sourceAttachFeature);
                }
                else {
                    sourceFS = (AnnotationFS) relationFS.getFeatureValue(sourceFeature);
                }

                AnnotationFS targetFS;
                if (targetAttachFeature != null) {
                    targetFS = (AnnotationFS) relationFS.getFeatureValue(targetFeature)
                            .getFeatureValue(targetAttachFeature);
                }
                else {
                    targetFS = (AnnotationFS) relationFS.getFeatureValue(targetFeature);
                }

                if (sourceFS == null || targetFS == null) {
                    var message = new StringBuilder();

                    message.append("Relation [" + adapter.getLayer().getName() + "] with id ["
                            + ICasUtil.getAddr(relationFS)
                            + "] has loose ends - cannot identify attached annotations.");
                    if (adapter.getAttachFeatureName() != null) {
                        message.append("\nRelation [" + adapter.getLayer().getName()
                                + "] attached to feature [" + adapter.getAttachFeatureName()
                                + "].");
                    }
                    message.append("\nSource: " + sourceFS);
                    message.append("\nTarget: " + targetFS);
                    LOG.warn("{}", message.toString());
                    continue;
                }

                var isIncoming = isSame(targetFS, aFs);
                var isOutgoing = isSame(sourceFS, aFs);

                if (isIncoming && isOutgoing) {
                    result.add(new AttachedAnnotation(layer, relationFS, sourceFS, LOOP));
                }
                else if (isIncoming) {
                    result.add(new AttachedAnnotation(layer, relationFS, sourceFS, INCOMING));
                }
                else if (isOutgoing) {
                    result.add(new AttachedAnnotation(layer, relationFS, targetFS, OUTGOING));
                }
            }
        }

        return result;
    }

    @Override
    @Transactional
    public List<AttachedAnnotation> getAttachedLinks(AnnotationLayer aLayer, AnnotationFS aFs)
    {
        var cas = aFs.getCAS();
        var result = new ArrayList<AttachedAnnotation>();
        var adapter = getAdapter(aLayer);
        if (adapter instanceof SpanAdapter) {
            // Check for incoming links - other annotations that link TO this annotation
            for (var linkFeature : listAttachedLinkFeatures(aLayer)) {
                if (linkFeature.getMultiValueMode() == ARRAY
                        && linkFeature.getLinkMode() == WITH_ROLE) {
                    // Fetch slot hosts that could link to the current FS and check if any of
                    // them actually links to the current FS
                    var linkHost = CasUtil.getType(cas, linkFeature.getLayer().getName());
                    for (var linkFS : cas.select(linkHost)) {
                        if (!(linkFS instanceof AnnotationFS)) {
                            continue;
                        }

                        List<LinkWithRoleModel> links = adapter.getFeatureValue(linkFeature,
                                linkFS);
                        for (var li = 0; li < links.size(); li++) {
                            var link = links.get(li);
                            var linkTarget = selectByAddr(cas, AnnotationFS.class, link.targetAddr);
                            // If the current annotation fills a slot, then add the slot host to
                            // our list of attached links.
                            if (isSame(linkTarget, aFs)) {
                                result.add(new AttachedAnnotation(linkFeature.getLayer(),
                                        (AnnotationFS) linkFS, INCOMING));
                            }
                        }
                    }
                }
            }

            // Check for outgoing links - this annotation links TO other annotations
            for (var feature : listSupportedFeatures(aLayer)) {
                if (feature.getMultiValueMode() == ARRAY && feature.getLinkMode() == WITH_ROLE) {
                    List<LinkWithRoleModel> links = adapter.getFeatureValue(feature, aFs);
                    for (var link : links) {
                        var linkTarget = selectByAddr(cas, AnnotationFS.class, link.targetAddr);
                        if (linkTarget != null) {
                            result.add(new AttachedAnnotation(aLayer, linkTarget, OUTGOING));
                        }
                    }
                }
            }
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSentenceLayerEditable(Project aProject)
    {
        if (!annotationEditorProperties.isSentenceLayerEditable()) {
            return false;
        }

        try {
            var layer = findLayer(aProject, Sentence.class.getName());
            return !layer.isReadonly() && layer.isEnabled();
        }
        catch (NoResultException e) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isTokenLayerEditable(Project aProject)
    {
        if (!annotationEditorProperties.isTokenLayerEditable()) {
            return false;
        }

        try {
            var layer = findLayer(aProject, Token.class.getName());
            return !layer.isReadonly() && layer.isEnabled();
        }
        catch (NoResultException e) {
            return false;
        }
    }

    @Override
    @Transactional
    public void createMissingTag(AnnotationFeature aFeature, String aValue)
        throws IllegalFeatureValueException
    {
        if (aValue == null || aFeature.getTagset() == null) {
            return;
        }

        if (existsTag(aValue, aFeature.getTagset())) {
            return;
        }

        if (!aFeature.getTagset().isCreateTag()) {
            throw new IllegalFeatureValueException("[" + aValue
                    + "] is not in the tag list. Please choose from the existing tags");
        }

        var selectedTag = new Tag();
        selectedTag.setName(aValue);
        selectedTag.setTagSet(aFeature.getTagset());
        createTag(selectedTag);
    }

    @Override
    public boolean hasValidLayerName(AnnotationLayer aLayer)
    {
        return validateLayerName(aLayer).isEmpty();
    }

    @Override
    public List<ValidationError> validateLayerName(AnnotationLayer aLayer)
    {
        var name = aLayer.getName();

        var errors = new ArrayList<ValidationError>();

        if (isTypeName(name)) {
            errors.add(new ValidationError(
                    "Invalid technical name [" + name + "]. Try using a simpler name when "
                            + "creating the layer and rename the layer after it has been created"));
            return errors;
        }

        if (existsLayer(name, aLayer.getProject())) {
            errors.add(new ValidationError(
                    "A layer with the name [" + name + "] already exists in this project."));
            return errors;
        }

        return errors;
    }

    @Override
    public boolean hasValidFeatureName(AnnotationFeature aFeature)
    {
        return validateFeatureName(aFeature).isEmpty();
    }

    @Override
    public List<ValidationError> validateFeatureName(AnnotationFeature aFeature)
    {
        var name = aFeature.getName();

        var errors = new ArrayList<ValidationError>();

        if (isBlank(name)) {
            errors.add(new ValidationError("Feature name cannot be empty."));
            return errors;
        }

        // Check if feature name is not from the restricted names list
        if (RESTRICTED_FEATURE_NAMES.contains(name)) {
            errors.add(new ValidationError("[" + name + "] is a reserved feature name. Please "
                    + "use a different name for the feature."));
            return errors;
        }

        if (name.contains(FEATURE_SUFFIX_SEP)) {
            errors.add(new ValidationError("[" + name + "] must not contain [__]. Please "
                    + "use a different name for the feature."));
            return errors;
        }

        var layerSupport = layerSupportRegistry.getLayerSupport(aFeature.getLayer());
        errors.addAll(layerSupport.validateFeatureName(aFeature));
        if (!errors.isEmpty()) {
            return errors;
        }

        // Checking if feature name doesn't start with a number or underscore
        // And only uses alphanumeric characters
        if (!isFeatureName(name)) {
            errors.add(new ValidationError("Invalid feature name [" + name
                    + "].  Feature names must start with a letter and consist only of letters, digits, or underscores."));
            return errors;
        }

        if (existsFeature(name, aFeature.getLayer())) {
            errors.add(new ValidationError(
                    "A feature with the name [" + name + "] already exists on this layer!"));
            return errors;
        }

        return errors;
    }

    @Override
    @Transactional
    public List<AnnotationLayer> getRelationLayersFor(AnnotationLayer aSpanLayer)
    {
        var candidates = new ArrayList<AnnotationLayer>();
        for (var layer : listEnabledLayers(aSpanLayer.getProject())) {
            if (!RelationLayerSupport.TYPE.equals(layer.getType())) {
                continue;
            }

            // Layer attaches explicitly to the given layer
            if (aSpanLayer.equals(layer.getAttachType())) {
                candidates.add(layer);
                continue;
            }

            // Relation layer that attaches to any span layer
            if (layer.getAttachType() == null) {
                candidates.add(layer);
                continue;
            }

            // Special case for built-in layers such as the Dependency layer
            if (layer.getAttachFeature() != null
                    && layer.getAttachFeature().getType().equals(aSpanLayer.getName())) {
                candidates.add(layer);
                continue;
            }
        }

        return candidates;
    }

    @Override
    public void addLayerAndFeatureDefinitionAnnotations(CAS aCas, List<AnnotationFeature> aFeatures)
    {
        var layerDefType = aCas.getTypeSystem().getType(TYPE_NAME_LAYER_DEFINITION);
        var featureDefType = aCas.getTypeSystem().getType(TYPE_NAME_FEATURE_DEFINITION);

        var featuresGroupedByLayer = aFeatures.stream() //
                .collect(groupingBy(AnnotationFeature::getLayer));

        var layers = featuresGroupedByLayer.keySet().stream() //
                .sorted(comparing(AnnotationLayer::getName)) //
                .toList();

        for (var layer : layers) {
            final var layerDefFs = aCas.createFS(layerDefType);
            setFeature(layerDefFs, FEATURE_BASE_NAME_NAME, layer.getName());
            setFeature(layerDefFs, FEATURE_BASE_NAME_UI_NAME, layer.getUiName());
            aCas.addFsToIndexes(layerDefFs);

            var features = featuresGroupedByLayer.get(layer).stream() //
                    .sorted(comparing(AnnotationFeature::getName)) //
                    .toList();

            for (var feature : features) {
                final var featureDefFs = aCas.createFS(featureDefType);
                setFeature(featureDefFs, FEATURE_BASE_NAME_LAYER, layerDefFs);
                setFeature(featureDefFs, FEATURE_BASE_NAME_NAME, feature.getName());
                setFeature(featureDefFs, FEATURE_BASE_NAME_UI_NAME, feature.getUiName());
                aCas.addFsToIndexes(featureDefFs);
            }
        }
    }

    @Override
    public void addTagsetDefinitionAnnotations(CAS aCas, List<AnnotationFeature> aFeatures)
    {
        for (var feature : aFeatures) {
            var tagSet = feature.getTagset();
            if (tagSet == null || ChainLayerSupport.TYPE.equals(feature.getLayer().getType())) {
                continue;
            }

            var layerName = feature.getLayer().getName();
            var tagSetName = tagSet.getName();

            var tagSetModified = false;
            // modify existing tagset Name
            for (var fs : aCas.select(TagsetDescription.class)) {
                var existingLayer = fs.getLayer();
                var existingTagSetName = fs.getName();
                if (existingLayer.equals(layerName)) {
                    // only if the tagset name is changed
                    if (!tagSetName.equals(existingTagSetName)) {
                        fs.setName(tagSetName);
                        aCas.addFsToIndexes(fs);
                    }
                    tagSetModified = true;
                    break;
                }
            }

            if (!tagSetModified) {
                var fs = new TagsetDescription(aCas.getJCasImpl());
                fs.setLayer(layerName);
                fs.setName(tagSetName);
                aCas.addFsToIndexes(fs);
            }
        }
    }

    private static final String NAMESPACE_SEPARATOR_AS_STRING = "" + TypeSystem.NAMESPACE_SEPARATOR;

    // Remove method when upgrading to UIMA 3.6.0
    // See https://github.com/apache/uima-uimaj/issues/369
    // return TypeSystemUtil.isTypeName(name);
    public static boolean isTypeName(String name)
    {
        var tok = new StringTokenizer(name, NAMESPACE_SEPARATOR_AS_STRING, true);
        while (tok.hasMoreTokens()) {
            if (!isFeatureName(tok.nextToken())) {
                return false;
            }
            if (tok.hasMoreTokens()) {
                if (!tok.nextToken().equals(NAMESPACE_SEPARATOR_AS_STRING)
                        || !tok.hasMoreTokens()) {
                    return false;
                }
            }
        }
        return true;
    }
}
