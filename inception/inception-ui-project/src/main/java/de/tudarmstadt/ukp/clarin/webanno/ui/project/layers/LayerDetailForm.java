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
package de.tudarmstadt.ukp.clarin.webanno.ui.project.layers;

import static de.tudarmstadt.ukp.inception.support.lambda.LambdaBehavior.enabledWhen;
import static de.tudarmstadt.ukp.inception.support.lambda.LambdaBehavior.visibleWhen;
import static java.lang.Character.isJavaIdentifierStart;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Objects.isNull;
import static java.util.stream.Collectors.toCollection;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.form.AjaxFormComponentUpdatingBehavior;
import org.apache.wicket.extensions.ajax.markup.html.modal.ModalDialog;
import org.apache.wicket.feedback.IFeedback;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.CheckBox;
import org.apache.wicket.markup.html.form.ChoiceRenderer;
import org.apache.wicket.markup.html.form.DropDownChoice;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextArea;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.panel.EmptyPanel;
import org.apache.wicket.model.CompoundPropertyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.apache.wicket.util.resource.IResourceStream;

import de.tudarmstadt.ukp.clarin.webanno.api.casstorage.CasStorageService;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.ui.project.layers.ProjectLayersPanel.FeatureSelectionForm;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import de.tudarmstadt.ukp.inception.annotation.layer.relation.api.RelationLayerSupport;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.SpanLayerSupport;
import de.tudarmstadt.ukp.inception.bootstrap.BootstrapModalDialog;
import de.tudarmstadt.ukp.inception.documents.api.DocumentService;
import de.tudarmstadt.ukp.inception.export.LayerImportExportUtils;
import de.tudarmstadt.ukp.inception.schema.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.inception.schema.api.config.AnnotationSchemaProperties;
import de.tudarmstadt.ukp.inception.schema.api.event.LayerConfigurationChangedEvent;
import de.tudarmstadt.ukp.inception.schema.api.layer.LayerSupport;
import de.tudarmstadt.ukp.inception.schema.api.layer.LayerSupportRegistry;
import de.tudarmstadt.ukp.inception.schema.api.layer.LayerType;
import de.tudarmstadt.ukp.inception.support.help.DocLink;
import de.tudarmstadt.ukp.inception.support.lambda.LambdaAjaxButton;
import de.tudarmstadt.ukp.inception.support.lambda.LambdaAjaxLink;
import de.tudarmstadt.ukp.inception.support.lambda.LambdaModelAdapter;
import de.tudarmstadt.ukp.inception.support.spring.ApplicationEventPublisherHolder;
import de.tudarmstadt.ukp.inception.support.wicket.AjaxDownloadLink;
import de.tudarmstadt.ukp.inception.support.wicket.InputStreamResourceStream;
import de.tudarmstadt.ukp.inception.support.wicket.WicketExceptionUtil;

public class LayerDetailForm
    extends Form<AnnotationLayer>
{
    private static final long serialVersionUID = -1L;

    private static final String MID_TRAITS_CONTAINER = "traitsContainer";
    private static final String MID_TRAITS = "traits";

    private static final String TYPE_PREFIX = "webanno.custom.";

    private @SpringBean LayerSupportRegistry layerSupportRegistry;
    private @SpringBean AnnotationSchemaService annotationService;
    private @SpringBean AnnotationSchemaProperties annotationSchemaProperties;
    private @SpringBean DocumentService documentService;
    private @SpringBean CasStorageService casStorageService;
    private @SpringBean ApplicationEventPublisherHolder applicationEventPublisherHolder;

    private DropDownChoice<LayerType> layerTypeSelect;
    private DropDownChoice<AnnotationLayer> attachTypeSelect;
    private Label effectiveAttachType;
    private TextField<String> uiName;
    private TextField<String> shortName;

    private FeatureSelectionForm featureSelectionForm;
    private FeatureDetailForm featureDetailForm;

    private WebMarkupContainer traitsContainer;
    private ModalDialog confirmationDialog;

    public LayerDetailForm(String id, IModel<AnnotationLayer> aSelectedLayer,
            FeatureSelectionForm aFeatureSelectionForm, FeatureDetailForm aFeatureDetailForm)
    {
        super(id, CompoundPropertyModel.of(aSelectedLayer));

        add(new DocLink("propertiesHelpLink", "sect_projects_layers_properties"));
        add(new DocLink("behavioursHelpLink", "sect_projects_layers_behaviours"));

        featureSelectionForm = aFeatureSelectionForm;
        featureDetailForm = aFeatureDetailForm;

        setOutputMarkupPlaceholderTag(true);

        add(traitsContainer = new WebMarkupContainer(MID_TRAITS_CONTAINER));
        traitsContainer.setOutputMarkupId(true);

        uiName = new TextField<String>("uiName");
        uiName.setOutputMarkupId(true);
        uiName.setRequired(true);
        add(uiName);

        shortName = new TextField<String>("shortName");
        shortName.setOutputMarkupId(true);
        add(shortName);

        add(new TextArea<String>("description").setOutputMarkupPlaceholderTag(true));

        add(new Label("name").add(visibleWhen(() -> isNotBlank(getModelObject().getName()))));

        add(new CheckBox("enabled").setOutputMarkupPlaceholderTag(true));

        add(layerTypeSelect = new DropDownChoice<LayerType>("type")
        {
            private static final long serialVersionUID = 9029205407108101183L;

            @Override
            protected void onModelChanged()
            {
                // If the feature type has changed, we need to set up a new traits editor
                Component newTraits;
                if (LayerDetailForm.this.getModelObject() != null && getModelObject() != null) {
                    LayerSupport<?, ?> fs = layerSupportRegistry
                            .getLayerSupport(getModelObject().getlayerSupportId());
                    newTraits = fs.createTraitsEditor(MID_TRAITS, LayerDetailForm.this.getModel());
                }
                else {
                    newTraits = new EmptyPanel(MID_TRAITS);
                }

                traitsContainer.addOrReplace(newTraits);
            }
        });
        layerTypeSelect.setChoices(layerSupportRegistry::getAllTypes);
        layerTypeSelect.add(enabledWhen(() -> isNull(getModelObject().getId())));
        layerTypeSelect.setRequired(true);
        layerTypeSelect.setNullValid(false);
        layerTypeSelect.setChoiceRenderer(new ChoiceRenderer<>("uiName"));
        layerTypeSelect.setModel(
                LambdaModelAdapter.of(() -> layerSupportRegistry.getLayerType(getModelObject()),
                        (v) -> getModelObject().setType(v.getName())));
        layerTypeSelect.add(new AjaxFormComponentUpdatingBehavior("change")
        {
            private static final long serialVersionUID = 6790949494089940303L;

            @Override
            protected void onUpdate(AjaxRequestTarget aTarget)
            {
                aTarget.add(attachTypeSelect);
                aTarget.add(traitsContainer);
            }
        });

        attachTypeSelect = new DropDownChoice<AnnotationLayer>("attachType",
                LoadableDetachableModel.of(this::getAttachLayerChoices),
                new ChoiceRenderer<>("uiName"))
        {
            private static final long serialVersionUID = -7022036247442205106L;

            @Override
            protected String getNullValidKey()
            {
                if (annotationSchemaProperties.isCrossLayerRelationsEnabled()) {
                    return getId() + ".any";
                }

                return super.getNullValidKey();
            };
        };
        attachTypeSelect.setNullValid(true);
        attachTypeSelect.add(visibleWhen(() -> isNull(getModelObject().getId())
                && RelationLayerSupport.TYPE.equals(getModelObject().getType())));
        attachTypeSelect.setOutputMarkupPlaceholderTag(true);
        add(attachTypeSelect);

        effectiveAttachType = new Label("effectiveAttachType",
                LoadableDetachableModel.of(this::getEffectiveAttachTypeUiName));
        effectiveAttachType.setOutputMarkupPlaceholderTag(true);
        effectiveAttachType.add(visibleWhen(() -> !isNull(getModelObject().getId())
                && RelationLayerSupport.TYPE.equals(getModelObject().getType())));
        add(effectiveAttachType);

        // Behaviors of layers
        add(new CheckBox("readonly").setOutputMarkupPlaceholderTag(true));

        add(new AjaxDownloadLink("exportLayersAsJson", this::exportLayerAsJson));
        add(new AjaxDownloadLink("exportAllLayersAsJson", this::exportAllLayersAsJson));
        add(new AjaxDownloadLink("exportLayersAsUima", this::exportAllLayersAsUimaXml));
        add(new AjaxDownloadLink("exportFullTypeSystemAsUima",
                this::exportFullTypeSystemAsUimaXml));

        // Processing the data in onAfterSubmit so the traits panel can use the
        // override onSubmit in its nested form and store the traits before
        // we clear the currently selected feature.
        add(new LambdaAjaxButton<AnnotationLayer>("save", this::actionSave).triggerAfterSubmit());
        add(new LambdaAjaxButton<AnnotationLayer>("delete", this::actionDelete) //
                .add(enabledWhen(() -> !isNull(getModelObject().getId()))));
        add(new LambdaAjaxLink("cancel", this::actionCancel));

        queue(confirmationDialog = new BootstrapModalDialog("confirmationDialog").trapFocus());
    }

    private String getEffectiveAttachTypeUiName()
    {
        var layer = LayerDetailForm.this.getModelObject();

        if (layer.getAttachType() == null) {
            if (RelationLayerSupport.TYPE.equals(layer.getType())) {
                return "Any span";
            }

            return null;
        }

        if (layer.getAttachFeature() != null) {
            var project = getModelObject().getProject();
            var actualAttachLayer = annotationService.findLayer(project,
                    layer.getAttachFeature().getType());
            return String.format("%s :: %s -> %s", layer.getAttachType().getUiName(),
                    layer.getAttachFeature().getUiName(), actualAttachLayer.getUiName());
        }

        return layer.getAttachType().getUiName();
    }

    /**
     * Gets the list of annotation layers to which a relation layer may attach.
     */
    private List<AnnotationLayer> getAttachLayerChoices()
    {
        var project = getModelObject().getProject();
        var layer = LayerDetailForm.this.getModelObject();

        // If the layer has already been created, the attach layer cannot be changed anymore.
        // So in this case, we return either an empty list of a list with exactly the configured
        // attach layer in it.
        if (layer.getId() != null) {
            if (layer.getAttachType() == null) {
                return emptyList();
            }

            if (layer.getAttachFeature() != null) {
                var actualAttachLayer = annotationService.findLayer(project,
                        layer.getAttachFeature().getType());
                return asList(actualAttachLayer);
            }

            return asList(layer.getAttachType());
        }

        // Attach layers are only valid for relation layers.
        if (!RelationLayerSupport.TYPE.equals(layer.getType())) {
            return emptyList();
        }

        // Get all the layers
        var allLayers = annotationService.listAnnotationLayer(project);

        // Candidates for attach-layers are only span layers, so lets filter these
        var candidateLayers = allLayers.stream()
                .filter(l -> SpanLayerSupport.TYPE.equals(l.getType()))
                .filter(l -> !Token._TypeName.equals(l.getName())
                        && !Sentence._TypeName.equals(l.getName()))
                .collect(toCollection(ArrayList::new));

        // Further narrow down the candidates by removing all layers which are already the target
        // of an attachment
        for (var l : allLayers) {
            if (l.getAttachType() != null) {
                // If an attach-feature is configured, then remove the layer to which this feature
                // points from the candidate list
                if (l.getAttachFeature() != null) {
                    var actualAttachLayer = annotationService.findLayer(project,
                            l.getAttachFeature().getType());
                    candidateLayers.remove(actualAttachLayer);
                }
                // If no attach-feature is configured, the the attach-layer is the target layer of
                // the attachment, so remove it from the candidate list
                else {
                    candidateLayers.remove(l.getAttachType());
                }
            }
        }

        return candidateLayers;
    }

    private boolean isLayerDeletable(AnnotationLayer aLayer)
    {
        return annotationService.listAttachedRelationLayers(aLayer).isEmpty()
                && annotationService.listAttachedLinkFeatures(aLayer).isEmpty();
    }

    private void actionDelete(AjaxRequestTarget aTarget, Form<AnnotationLayer> aForm)
    {
        var dialogContent = new DeleteLayerConfirmationDialogContentPanel(ModalDialog.CONTENT_ID,
                getModel());

        dialogContent.setConfirmAction((_target) -> actionDeleteLayerConfirmed(_target));

        confirmationDialog.open(dialogContent, aTarget);
    }

    private void actionDeleteLayerConfirmed(AjaxRequestTarget _target) throws IOException
    {
        annotationService.removeLayer(getModelObject());
        var project = getModelObject().getProject();
        setModelObject(null);
        documentService.upgradeAllAnnotationDocuments(project);
        // Trigger LayerConfigurationChangedEvent
        applicationEventPublisherHolder.get()
                .publishEvent(new LayerConfigurationChangedEvent(this, project));
        _target.add(getPage());
    }

    private void actionSave(AjaxRequestTarget aTarget, Form<AnnotationLayer> aForm)
    {
        aTarget.add(getParent());
        aTarget.addChildren(getPage(), IFeedback.class);

        var layer = aForm.getModelObject();

        final var project = layer.getProject();

        // Set type name only when the layer is initially created. After that, only the UI
        // name may be updated. Also any validation related to the type name only needs to
        // happen on the initial creation.
        var isNewLayer = isNull(layer.getId());
        if (isNewLayer) {
            var shortLayerName = StringUtils.capitalize(layer.getUiName());
            shortLayerName = shortLayerName.replaceAll("\\W", "");
            var layerName = TYPE_PREFIX + shortLayerName;

            if (shortLayerName.isEmpty()) {
                error("Unable to derive internal name from [" + layer.getUiName()
                        + "]. Please choose a different initial name and rename after the "
                        + "layer has been created.");
                return;
            }

            if (!isJavaIdentifierStart(shortLayerName.charAt(0))) {
                error("Initial layer name cannot start with [" + shortLayerName.charAt(0)
                        + "]. Please choose a different initial name and rename after the "
                        + "layer has been created.");
                return;
            }

            if (annotationService.existsLayer(layerName, project)) {
                error("A layer with the name [" + layerName + "] already exists in this project.");
                return;
            }

            if (annotationService.existsLayerWithShortName(shortLayerName, project)) {
                error("A layer with the short name [" + shortLayerName
                        + "] already exists in this project.");
                return;
            }

            layer.setShortName(shortLayerName);
            layer.setName(layerName);
        }

        if (!annotationSchemaProperties.isCrossLayerRelationsEnabled()) {
            if (RelationLayerSupport.TYPE.equals(layer.getType())
                    && layer.getAttachType() == null) {
                error("A relation layer needs to attach to a span layer.");
                return;
            }
        }

        annotationService.createOrUpdateLayer(layer);

        // Initialize default features if necessary but only after the layer has actually been
        // persisted in the database.
        if (isNewLayer) {
            var adapter = annotationService.getAdapter(layer);
            adapter.initializeLayerConfiguration(annotationService);
        }

        success("Settings for layer [" + layer.getUiName() + "] saved.");
        aTarget.add(findParent(ProjectLayersPanel.class));
        aTarget.add(featureDetailForm);
        aTarget.add(featureSelectionForm);

        // Trigger LayerConfigurationChangedEvent
        applicationEventPublisherHolder.get()
                .publishEvent(new LayerConfigurationChangedEvent(this, project));
    }

    private void actionCancel(AjaxRequestTarget aTarget)
    {
        setModelObject(null);
        featureDetailForm.setModelObject(null);

        aTarget.add(getParent());
        aTarget.addChildren(getPage(), IFeedback.class);
    }

    private IResourceStream exportFullTypeSystemAsUimaXml()
    {
        try (var bos = new ByteArrayOutputStream()) {
            var tsd = annotationService.getFullProjectTypeSystem(getModelObject().getProject(),
                    false);
            tsd.toXML(bos);
            return new InputStreamResourceStream(new ByteArrayInputStream(bos.toByteArray()),
                    "full-typesystem.xml");
        }
        catch (Exception e) {
            WicketExceptionUtil.handleException(ProjectLayersPanel.LOG, this, e);
            return null;
        }
    }

    private IResourceStream exportAllLayersAsUimaXml()
    {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            var tsd = annotationService.getAllProjectTypes(getModelObject().getProject());
            tsd.toXML(bos);
            return new InputStreamResourceStream(new ByteArrayInputStream(bos.toByteArray()),
                    "layers-typesystem.xml");
        }
        catch (Exception e) {
            WicketExceptionUtil.handleException(ProjectLayersPanel.LOG, this, e);
            return null;
        }
    }

    private IResourceStream exportLayerAsJson()
    {
        try {
            String json = LayerImportExportUtils.exportLayerToJson(annotationService,
                    getModelObject());
            return new InputStreamResourceStream(new ByteArrayInputStream(json.getBytes(UTF_8)),
                    "layer.json");
        }
        catch (Exception e) {
            WicketExceptionUtil.handleException(ProjectLayersPanel.LOG, this, e);
            return null;
        }
    }

    private IResourceStream exportAllLayersAsJson()
    {
        try {
            var project = getModelObject().getProject();
            var layers = annotationService.listAnnotationLayer(project);
            String json = LayerImportExportUtils.exportLayersToJson(annotationService, layers);
            return new InputStreamResourceStream(new ByteArrayInputStream(json.getBytes(UTF_8)),
                    "all-layers.json");
        }
        catch (Exception e) {
            WicketExceptionUtil.handleException(ProjectLayersPanel.LOG, this, e);
            return null;
        }
    }

    @Override
    protected void onModelChanged()
    {
        super.onModelChanged();

        // Since feature type uses a lambda model, it needs to be notified explicitly.
        layerTypeSelect.modelChanged();
    }

    @Override
    protected void onConfigure()
    {
        super.onConfigure();

        setVisible(getModelObject() != null);
    }

    public Component getInitialFocusComponent()
    {
        return uiName;
    }
}
