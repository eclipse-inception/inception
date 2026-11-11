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
package de.tudarmstadt.ukp.inception.schema.api;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.apache.uima.UIMAException;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.apache.wicket.validation.ValidationError;

import de.tudarmstadt.ukp.clarin.webanno.api.casstorage.CasUpgradeMode;
import de.tudarmstadt.ukp.clarin.webanno.api.type.CASMetadata;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.ImmutableTag;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.ReorderableTag;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.Tag;
import de.tudarmstadt.ukp.clarin.webanno.model.TagSet;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import de.tudarmstadt.ukp.inception.schema.api.adapter.IllegalFeatureValueException;
import de.tudarmstadt.ukp.inception.schema.api.adapter.TypeAdapter;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureSupport;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureSupportRegistry;
import de.tudarmstadt.ukp.inception.schema.api.layer.LayerSupport;
import de.tudarmstadt.ukp.inception.schema.api.layer.LayerSupportRegistry;

/**
 * This interface contains methods which are related to TagSet, Tag and Type for the annotation
 * project.
 */
public interface AnnotationSchemaService
{
    String SERVICE_NAME = "annotationService";

    static final String FEATURE_BASE_NAME_UI_NAME = "uiName";
    static final String FEATURE_BASE_NAME_NAME = "name";
    static final String FEATURE_BASE_NAME_LAYER = "layer";
    static final String TYPE_NAME_FEATURE_DEFINITION = "de.tudarmstadt.ukp.clarin.webanno.api.type.FeatureDefinition";
    static final String TYPE_NAME_LAYER_DEFINITION = "de.tudarmstadt.ukp.clarin.webanno.api.type.LayerDefinition";

    /**
     * Used when generating INCEpTION-specific feature names between a base name defined by the user
     * and an INCEpTION-specific suffix.
     */
    static final String FEATURE_SUFFIX_SEP = "__";

    /**
     * Creates a {@link Tag}. Combination of {@code tag name} and {@code tagset name} should be
     * unique.
     *
     * @param tag
     *            the tag.
     */
    void createTag(Tag tag);

    /**
     * Creates multiple {@link Tag tags} at once. Combination of {@code tag name} and
     * {@code tagset name} should be unique.
     *
     * @param tag
     *            the tag.
     */
    void createTags(Tag... tag);

    void updateTagRanks(TagSet aTagSet, List<Tag> aTags);

    void updateFeatureRanks(AnnotationLayer aLayer, List<AnnotationFeature> aTags);

    /**
     * creates a {@link TagSet} object in the database
     *
     * @param tagset
     *            the tagset.
     */
    void createTagSet(TagSet tagset);

    /**
     * creates a type which will be a span, chain, or arc(relation) type. Currently the annotation
     * types are highly highly tied with the tagsets, one tagset per type. POS, Names Entity, and
     * coreference links are span types while coreference chains and dependency parsings are
     * arc(relation) types.
     *
     * @param type
     *            the type.
     */
    void createOrUpdateLayer(AnnotationLayer type);

    void createFeature(AnnotationFeature feature);

    /**
     * Get Tag by its ID
     * 
     * @param id
     *            the tag id.
     * @return the tag.
     */
    Optional<Tag> getTag(long id);

    /**
     * gets a {@link Tag} using its name and a {@link TagSet}
     * 
     * @param tagName
     *            the tag name.
     * @param tagSet
     *            the tagset.
     * @return the tag.
     */
    Optional<Tag> getTag(String tagName, TagSet tagSet);

    /**
     * Check if a tag with this name in the given tagset exists
     * 
     * @param tagName
     *            the tag name.
     * @param tagSet
     *            the tagset.
     * @return if the tag exists.
     */
    boolean existsTag(String tagName, TagSet tagSet);

    /**
     * Check if a {@link TagSet} with this name exists
     * 
     * @param name
     *            the tagset name.
     * @param project
     *            the project.
     * @return if the tagset exists.
     */
    boolean existsTagSet(String name, Project project);

    /**
     * check if a {@link TagSet} in this {@link Project} exists
     * 
     * @param project
     *            the project.
     * @return if any tagset exists.
     */
    boolean existsTagSet(Project project);

    /**
     * Check if any {@link AnnotationLayer} exists with this name in the given {@link Project}.
     * 
     * @param project
     *            the project.
     * @return if a layer exists.
     */
    boolean existsLayer(Project project);

    /**
     * Check if an {@link AnnotationLayer} exists with this name in the given {@link Project}.
     * 
     * @param name
     *            the layer name.
     * @param project
     *            the project.
     * @return if the layer exists.
     */
    boolean existsLayer(String name, Project project);

    /**
     * Check if an {@link AnnotationLayer} exists with this short name in the given {@link Project}.
     * 
     * @param shortName
     *            the layer name.
     * @param project
     *            the project.
     * @return if the layer exists.
     */
    boolean existsLayerWithShortName(String shortName, Project project);

    /**
     * check if an {@link AnnotationLayer} exists with this name and type in this {@link Project}
     * 
     * @param name
     *            the layer name.
     * @param type
     *            the layer type.
     * @param project
     *            the project.
     * @return if the layer exists.
     */
    boolean existsLayer(String name, String type, Project project);

    boolean existsEnabledLayerOfType(Project project, String type);

    /**
     * Check if this {@link AnnotationFeature} already exists
     * 
     * @param name
     *            the feature name.
     * @param type
     *            the feature type.
     * @return if the feature exists.
     */
    boolean existsFeature(String name, AnnotationLayer type);

    boolean existsEnabledFeatureOfType(Project aProject, String aType);

    /**
     * get a {@link TagSet} with this name in a {@link Project}
     * 
     * @param name
     *            the tagset name.
     * @param project
     *            the project.
     * @return the tagset.
     */
    TagSet getTagSet(String name, Project project);

    /**
     * Get Tagset by its ID
     * 
     * @param id
     *            the tagset id.
     * @return the tagset.
     */
    TagSet getTagSet(long id);

    /**
     * Get an annotation layer using its id
     * 
     * @param id
     *            the layer id.
     * @return the layer.
     */
    AnnotationLayer getLayer(long id);

    /**
     * Get an annotation layer using its id. This method additionally ensures that the retrieved
     * layer is part of the given project. For security reasons, this method should be preferred
     * over {@link #getLayer(long)} if a project context is available.
     * 
     * @param aProject
     *            the project.
     * @param aLayerId
     *            the layer id.
     * @return the layer.
     */
    Optional<AnnotationLayer> getLayer(Project aProject, long aLayerId);

    /**
     * Find the {@link AnnotationLayer} matching the given UIMA type name (if any). <b>Note:</b>
     * This method is unable to handle chain layers. Better use
     * {@link #findLayer(Project, FeatureStructure)}
     * 
     * @param aProject
     *            the project.
     * @param aUimaTypeName
     *            the layer name.
     * 
     * @return the layer.
     */
    AnnotationLayer findLayer(Project aProject, String aUimaTypeName);

    /**
     * Find the {@link AnnotationLayer} matching the type of the given feature structure (if any).
     * 
     * @param aProject
     *            the project.
     * @param aFS
     *            a UIMA feature structure.
     * 
     * @return the layer.
     */
    AnnotationLayer findLayer(Project aProject, FeatureStructure aFS);

    /**
     * Get a {@link AnnotationFeature} name using its ID.
     *
     * @param id
     *            the feature id.
     * @return the feature.
     */
    AnnotationFeature getFeature(long id);

    /**
     * Get an {@link AnnotationFeature} using its name
     * 
     * @param aFeature
     *            the feature name.
     * @param aLayer
     *            the feature type.
     * @return the feature.
     */
    AnnotationFeature getFeature(String aFeature, AnnotationLayer aLayer);

    Optional<AnnotationFeature> getFeature(long aLayerId, String aName);

    /**
     * Check if an {@link AnnotationLayer} already exists.
     * 
     * @param name
     *            the layer name.
     * @param type
     *            the layer type.
     * @return if the layer exists.
     */
    boolean existsType(String name, String type);

    /**
     * List all annotation types in a project. This includes disabled layers and layers for which no
     * {@link LayerSupport} can be obtained via the {@link LayerSupportRegistry}.
     * 
     * @param aProject
     *            the project.
     * @return the layers.
     */
    List<AnnotationLayer> listAnnotationLayer(Project aProject);

    /**
     * List all supported annotation layers in a project. This includes disabled layers. Supported
     * layers are such for which a {@link LayerSupport} is available in the
     * {@link LayerSupportRegistry}.
     * 
     * @param aProject
     *            the project.
     * @return the layers.
     */
    List<AnnotationLayer> listSupportedLayers(Project aProject);

    /**
     * List all relation layers that are attached directly or indirectly (via a attach feature) to
     * the given layer. This method is useful to identify relation layers affected by a span delete
     * operation.
     * 
     * @param layer
     *            typically a span layer to be deleted.
     * @return the relation layers attaching directly or indirectly to the given layer.
     */
    List<AnnotationLayer> listAttachedRelationLayers(AnnotationLayer layer);

    List<AttachedAnnotation> getAttachedRels(AnnotationLayer aLayer, AnnotationFS aFs);

    /**
     * List all link features that could potentially link to the annotations of the given layer.
     * These include link features that have the given layer as a target type as well as link
     * features that have {@link CAS#TYPE_NAME_ANNOTATION} as the target type.
     * 
     * @param layer
     *            the target layer.
     * @return the possible link features.
     */
    List<AnnotationFeature> listAttachedLinkFeatures(AnnotationLayer layer);

    /**
     * @param aLayer
     *            a layer
     * @return all features of the given layer that other span layers attach to. These are primarily
     *         features such as {@code lemma} on the {@link Token} layer.
     */
    List<AnnotationFeature> listAttachedSpanFeatures(AnnotationLayer aLayer);

    List<AttachedAnnotation> getAttachedLinks(AnnotationLayer aLayer, AnnotationFS aFs);

    /**
     * List all the features in a {@link AnnotationLayer} for this {@link Project}. This includes
     * disabled features.
     * 
     * @param aLayer
     *            the layer.
     * 
     * @return the features.
     */
    List<AnnotationFeature> listAnnotationFeature(AnnotationLayer aLayer);

    /**
     * List all features in the project. This includes disabled features.
     * 
     * @param project
     *            the project.
     * @return the features.
     */
    List<AnnotationFeature> listAnnotationFeature(Project project);

    /**
     * List all supported features in the project. This includes disabled and non-accessible
     * features. Supported features are features for which a {@link FeatureSupport} is available in
     * the {@link FeatureSupportRegistry}.
     * 
     * @param aProject
     *            the project.
     * @return the features.
     */
    List<AnnotationFeature> listSupportedFeatures(Project aProject);

    /**
     * List all supported features in the layer. This includes disabled and non-accessible features.
     * Supported features are features for which a {@link FeatureSupport} is available in the
     * {@link FeatureSupportRegistry}.
     * 
     * @param aLayer
     *            the layer.
     * @return the features.
     */
    List<AnnotationFeature> listSupportedFeatures(AnnotationLayer aLayer);

    /**
     * List enabled features in a {@link AnnotationLayer} for this {@link Project}. Enabled features
     * are also supported and accessible.
     * 
     * @param aLayer
     *            the layer.
     * 
     * @return the features.
     */
    List<AnnotationFeature> listEnabledFeatures(AnnotationLayer aLayer);

    /**
     * list all {@link Tag} in a {@link TagSet}
     *
     * @param tag
     *            the tagsset.
     * @return the tags.
     */
    List<Tag> listTags(TagSet tag);

    List<ImmutableTag> listTagsImmutable(TagSet tagSet);

    List<ReorderableTag> listTagsReorderable(TagSet tagSet);

    /**
     * list all {@link TagSet} in the system
     *
     * @return the tagsets.
     */
    List<TagSet> listTagSets();

    /**
     * List all {@link TagSet }s in a {@link Project}
     *
     * @param project
     *            the project.
     * @return the tagsets.
     */
    List<TagSet> listTagSets(Project project);

    /**
     * Removes a {@link Tag} from the database
     *
     * @param tag
     *            the tag.
     */
    void removeTag(Tag tag);

    /**
     * removes a {@link TagSet } from the database
     *
     * @param tagset
     *            the tagset.
     */
    void removeTagSet(TagSet tagset);

    /**
     * Removes all tags linked to a tagset
     * 
     * @param tagSet
     *            tagset to remove the tags from
     */
    void removeAllTags(TagSet tagSet);

    /**
     * Should be called with care. Only when a project hosting this feature is removed
     * 
     * @param feature
     *            the feature.
     */
    void removeFeature(AnnotationFeature feature);

    /**
     * Should be called with care. Only when a project hosting this layer is removed
     * 
     * @param type
     *            the type.
     */
    void removeLayer(AnnotationLayer type);

    TagSet createTagSet(String aDescription, String aLanguage, String aTagSetName, String[] aTags,
            String[] aTagDescription, Project aProject)
        throws IOException;

    /**
     * @param aProject
     *            a project
     * @return the custom types define in the project excluding built-in types.
     * 
     * @see #getAllProjectTypes(Project)
     */
    TypeSystemDescription getCustomProjectTypes(Project aProject);

    /**
     * @param aProject
     *            a project
     * @return the custom types define in the project including built-in types.
     * 
     * @see #getCustomProjectTypes(Project)
     * @throws ResourceInitializationException
     *             if there was an UIMA-level problem
     */
    TypeSystemDescription getAllProjectTypes(Project aProject)
        throws ResourceInitializationException;

    /**
     * @param aProject
     *            a project
     * @return the full type system for the project (including any types discovered on the classpath
     *         via uimaFIT) including any internal types such as {@link CASMetadata}.
     * @throws ResourceInitializationException
     *             if there was an UIMA-level problem
     */
    TypeSystemDescription getFullProjectTypeSystem(Project aProject)
        throws ResourceInitializationException;

    /**
     * @param aProject
     *            a project
     * @param aIncludeInternalTypes
     *            whether to include internal types such as {@link CASMetadata}.
     * @return the full type system for the project (including any types discovered on the classpath
     *         via uimaFIT)
     * @throws ResourceInitializationException
     *             if there was an UIMA-level problem
     */
    TypeSystemDescription getFullProjectTypeSystem(Project aProject, boolean aIncludeInternalTypes)
        throws ResourceInitializationException;

    /**
     * Upgrade the CAS to the current project type system. This also compacts the CAS and removes
     * any unreachable feature structures. This should be called at key points such as when the user
     * opens an annotation document via the open document dialog. It is a slow call. The upgraded
     * CAS is not automatically persisted - the calling code needs to take care of this. If the CAS
     * will not be persisted, it is usually a better idea to use {@link #upgradeCasIfRequired}.
     * 
     * @param aCas
     *            the CAS to upgrade
     * @param aAnnotationDocument
     *            the annotation document to which the CAS belongs
     * @throws UIMAException
     *             if there was an UIMA-level problem
     * @throws IOException
     *             if there was an I/O-level problem
     */
    void upgradeCas(CAS aCas, AnnotationDocument aAnnotationDocument)
        throws UIMAException, IOException;

    /**
     * In-place upgrade of the given CAS to the target type system. It is a slow call. The CAS is
     * not automatically persisted - the calling code needs to take care of this.
     * 
     * @param aCas
     *            the CAS to upgrade
     * @param aTargetTypeSystem
     *            the target type system to which the CAS should be upgraded
     * @throws UIMAException
     *             if there was an UIMA-level problem
     * @throws IOException
     *             if there was an I/O-level problem
     */
    void upgradeCas(CAS aCas, TypeSystemDescription aTargetTypeSystem)
        throws UIMAException, IOException;

    void upgradeCas(CAS aSourceCas, CAS aTargetCas, TypeSystemDescription aTargetTypeSystem)
        throws UIMAException, IOException;

    /**
     * @param aCas
     *            the CAS to upgrade
     * @param aSourceDocument
     *            the source document to which the CAS belongs
     * @param aUser
     *            the annotator user to whom the CAS belongs
     * @see #upgradeCas(CAS, SourceDocument, String)
     * @throws UIMAException
     *             if there was an UIMA-level problem
     * @throws IOException
     *             if there was an I/O-level problem
     */
    void upgradeCas(CAS aCas, SourceDocument aSourceDocument, String aUser)
        throws UIMAException, IOException;

    void upgradeCas(CAS aCas, SourceDocument aSourceDocument, String aUser, CasUpgradeMode aMode)
        throws UIMAException, IOException;

    /**
     * Better call {@link #upgradeCas(CAS, SourceDocument, String)} which also logs the action
     * nicely to the log files. This method here is rather for unconditional bulk use such as by the
     * CAS doctor.
     * 
     * @param aCas
     *            the CAS to upgrade
     * @param aProject
     *            the project to which the CAS belongs
     * @see #upgradeCas(CAS, SourceDocument, String)
     * @throws UIMAException
     *             if there was an UIMA-level problem
     * @throws IOException
     *             if there was an I/O-level problem
     */
    void upgradeCas(CAS aCas, Project aProject) throws UIMAException, IOException;

    /**
     * @param aCas
     *            the CAS to upgrade
     * @param aAnnotationDocument
     *            the annotation document to which the CAS belongs
     * @return if the given CAS is compatible with the current type system of the project to which
     *         it belongs and upgrades it if necessary. This should be preferred over the mandatory
     *         CAS upgrade if the CAS is loaded in a read-only mode or in scenarios where it is not
     *         saved later. <br>
     *         If multiple CASes need to be upgraded, use
     *         {@link #upgradeCasIfRequired(Iterable, Project)}.
     * @throws UIMAException
     *             if there was an UIMA-level problem
     * @throws IOException
     *             if there was an I/O-level problem
     */
    boolean upgradeCasIfRequired(CAS aCas, AnnotationDocument aAnnotationDocument)
        throws UIMAException, IOException;

    /**
     * @param aCas
     *            the CAS to upgrade
     * @param aSourceDocument
     *            the source document to which the CAS belongs
     * @return if the given CAS is compatible with the current type system of the project to which
     *         it belongs and upgrades it if necessary. This should be preferred over the mandatory
     *         CAS upgrade if the CAS is loaded in a read-only mode or in scenarios where it is not
     *         saved later. <br>
     *         If multiple CASes need to be upgraded, use
     *         {@link #upgradeCasIfRequired(Iterable, Project)}.
     * @throws UIMAException
     *             if there was an UIMA-level problem
     * @throws IOException
     *             if there was an I/O-level problem
     */
    boolean upgradeCasIfRequired(CAS aCas, SourceDocument aSourceDocument)
        throws UIMAException, IOException;

    /**
     * @param aCas
     *            the CAS to upgrade
     * @param aProject
     *            the project to which the CAS belongs
     * @return if the given CAS is compatible with the current type system of the project to which
     *         it belongs and upgrades it if necessary. This should be preferred over the mandatory
     *         CAS upgrade if the CAS is loaded in a read-only mode or in scenarios where it is not
     *         saved later. <br>
     *         This method can deal with null values in the iterable. It will simply skip them.
     * @throws UIMAException
     *             if there was an UIMA-level problem
     * @throws IOException
     *             if there was an I/O-level problem
     */
    boolean upgradeCasIfRequired(Iterable<CAS> aCas, Project aProject)
        throws UIMAException, IOException;

    TypeAdapter getAdapter(AnnotationLayer aLayer);

    TypeAdapter findAdapter(Project aProject, FeatureStructure aFS);

    void importUimaTypeSystem(Project aProject, TypeSystemDescription aTSD)
        throws ResourceInitializationException;

    boolean isSentenceLayerEditable(Project aProject);

    boolean isTokenLayerEditable(Project aProject);

    void createMissingTag(AnnotationFeature aFeature, String aValue)
        throws IllegalFeatureValueException;

    List<ValidationError> validateFeatureName(AnnotationFeature aFeature);

    boolean hasValidFeatureName(AnnotationFeature aFeature);

    boolean hasValidLayerName(AnnotationLayer aLayer);

    List<ValidationError> validateLayerName(AnnotationLayer aLayer);

    List<AnnotationFeature> listEnabledFeatures(Project aProject);

    List<AnnotationLayer> listEnabledLayers(Project aProject);

    List<AnnotationLayer> getRelationLayersFor(AnnotationLayer aSpanLayer);

    void addTagsetDefinitionAnnotations(CAS aCas, List<AnnotationFeature> aFeatures);

    void addLayerAndFeatureDefinitionAnnotations(CAS aCas, List<AnnotationFeature> aFeatures);
}
