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
package de.tudarmstadt.ukp.clarin.webanno.ui.annotation.editor;

import static de.agilecoders.wicket.extensions.markup.html.bootstrap.icon.FontAwesome7IconType.chevron_down_s;
import static de.agilecoders.wicket.extensions.markup.html.bootstrap.icon.FontAwesome7IconType.chevron_up_s;
import static de.tudarmstadt.ukp.inception.support.uima.Range.isUndefined;
import static de.tudarmstadt.ukp.clarin.webanno.api.annotation.page.AnnotationEditorManagerPrefs.KEY_ANNOTATION_EDITOR_MANAGER_PREFS;
import static de.tudarmstadt.ukp.clarin.webanno.api.casstorage.CasAccessMode.SHARED_READ_ONLY_ACCESS;
import static de.tudarmstadt.ukp.clarin.webanno.api.casstorage.CasUpgradeMode.AUTO_CAS_UPGRADE;
import static de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocumentStateChangeFlag.EXPLICIT_ANNOTATOR_USER_ACTION;
import static de.tudarmstadt.ukp.inception.rendering.selection.FocusPosition.TOP;
import static de.tudarmstadt.ukp.inception.support.lambda.LambdaBehavior.visibleWhen;
import static de.tudarmstadt.ukp.inception.support.wicket.WicketUtil.wrapInTryCatch;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.apache.wicket.event.Broadcast.BREADTH;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.Validate;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.wicket.event.IEvent;
import org.apache.wicket.AttributeModifier;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.Component;
import org.apache.wicket.RestartResponseException;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.feedback.IFeedback;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.panel.EmptyPanel;
import org.apache.wicket.markup.html.panel.GenericPanel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LambdaModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.request.RequestHandlerExecutor.ReplaceHandlerException;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.wicketstuff.event.annotation.OnEvent;

import de.agilecoders.wicket.core.markup.html.bootstrap.image.Icon;
import de.tudarmstadt.ukp.inception.support.uima.Range;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.actionbar.ActionBar;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.exception.NotEditableException;
import de.tudarmstadt.ukp.inception.rendering.paging.NoPagingStrategy;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.preferences.UserPreferencesService;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationSet;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.security.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.actionbar.open.OpenDocumentDialog;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.component.DocumentNamePanel;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.detail.AnnotationDetailEditorPanel;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.event.AnchoringModeChangedEvent;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.event.DefaultLayerChangedEvent;
import de.tudarmstadt.ukp.clarin.webanno.ui.core.page.ApplicationPageBase;
import de.tudarmstadt.ukp.inception.annotation.events.AnnotationEvent;
import de.tudarmstadt.ukp.inception.annotation.events.FeatureValueUpdatedEvent;
import de.tudarmstadt.ukp.inception.documents.api.DocumentAccess;
import de.tudarmstadt.ukp.inception.documents.api.DocumentService;
import de.tudarmstadt.ukp.inception.editor.AnnotationEditorBase;
import de.tudarmstadt.ukp.inception.editor.AnnotationEditorFactory;
import de.tudarmstadt.ukp.inception.editor.AnnotationEditorRegistry;
import de.tudarmstadt.ukp.inception.editor.ContextMenuLookup;
import de.tudarmstadt.ukp.inception.preferences.PreferencesService;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotationActionHandler;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotationException;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotationPreferencesChangedEvent;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotatorState;
import de.tudarmstadt.ukp.inception.rendering.editorstate.DocumentEditor;
import de.tudarmstadt.ukp.inception.rendering.editorstate.DocumentEditorManager;
import de.tudarmstadt.ukp.inception.rendering.selection.ActiveEditorChangedEvent;
import de.tudarmstadt.ukp.inception.rendering.selection.AnnotatorViewportChangedEvent;
import de.tudarmstadt.ukp.inception.rendering.selection.DocumentStateChangedInEditorEvent;
import de.tudarmstadt.ukp.inception.rendering.selection.EditorContentReplacedEvent;
import de.tudarmstadt.ukp.inception.rendering.selection.EditorSetChangedEvent;
import de.tudarmstadt.ukp.inception.rendering.selection.Selection;
import de.tudarmstadt.ukp.inception.rendering.selection.SelectionChangedEvent;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VID;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VRange;
import de.tudarmstadt.ukp.inception.schema.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.inception.support.lambda.LambdaAjaxLink;
import jakarta.persistence.NoResultException;

/**
 * Panel containing an editor and its furniture (in particular its action bar).
 */
public class DocumentEditorPanel
    extends GenericPanel<AnnotatorState>
    implements DocumentEditor
{
    private static final long serialVersionUID = 4988739871189752144L;

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final String MID_EDITOR = "editor";
    private static final String MID_EDITOR_PANEL_ROOT = "editorPanelRoot";

    private static final String CSS_ACTIVE_EDITOR = "active-editor";
    private static final String MID_NUMBER_OF_PAGES = "numberOfPages";
    private static final String MID_ACTION_BAR_GROUPS = "actionBarGroups";
    private static final String MID_OWN_ACTION_BAR_GROUP = "ownActionBarGroup";
    private static final String MID_ACTION_BAR_ITEMS = "actionBarItems";
    private static final String MID_ACTION_BAR_ITEMS_BEFORE = "actionBarItemsBefore";
    private static final String MID_ACTION_BAR_ITEMS_AFTER = "actionBarItemsAfter";
    private static final String MID_SUBSTITUTED_EDITOR = "substitutedEditor";
    private static final String MID_DOCUMENT_STATUS_BADGES = "documentStatusBadges";
    private static final String MID_CLOSE_EDITOR = "closeEditor";
    private static final String MID_NO_DOCUMENT_PROMPT = "noDocumentPrompt";
    private static final String MID_OPEN_DOCUMENT_FROM_PROMPT = "openDocumentFromPrompt";

    private @SpringBean DocumentService documentService;
    private @SpringBean UserDao userRepository;
    private @SpringBean AnnotationEditorRegistry editorRegistry;
    private @SpringBean PreferencesService preferencesService;
    private @SpringBean AnnotationSchemaService annotationSchemaService;
    private @SpringBean UserPreferencesService userPreferencesService;
    private @SpringBean DocumentAccess documentAccess;

    private final WebMarkupContainer editorPanelRoot;
    private final WebMarkupContainer editorContainer;
    private final LambdaAjaxLink closeEditor;
    private final DocumentNamePanel documentNamePanel;
    private final WebMarkupContainer actionBar;
    private final LambdaAjaxLink actionBarToggle;
    private final ActionBar actionBarItems;

    private boolean actionBarCollapsed = false;

    private AnnotationEditorBase editor;

    private String loadedContentKey;

    private String substituteEditorFactoryName;

    private final LoadableDetachableModel<String> notEditableReason = LoadableDetachableModel
            .of(this::loadNotEditableReason);

    private final DocumentEditorManager manager;

    /**
     * @param aId
     *            the component id
     * @param aManager
     *            the manager owning this panel, i.e. the one that tracks which editor is active.
     *            The panel activates itself against it and hands it to the editors it creates.
     * @param aModel
     *            the annotator state driving this panel. The caller owns it and is expected to have
     *            set at least the project and the data owner, and to have loaded the annotation
     *            preferences into it. Give the panel a state of its own (rather than sharing the
     *            one of another editor) so that paging and selection here affect nothing else.
     */
    public DocumentEditorPanel(String aId, DocumentEditorManager aManager,
            IModel<AnnotatorState> aModel)
    {
        super(aId, aModel);

        Validate.notNull(aManager, "Document editor manager must be provided");

        manager = aManager;

        setOutputMarkupPlaceholderTag(true);

        editorPanelRoot = new WebMarkupContainer(MID_EDITOR_PANEL_ROOT);
        editorPanelRoot.setOutputMarkupId(true);
        editorPanelRoot.add(new AttributeAppender("class",
                LambdaModel.of(() -> isActiveEditorIndicated() ? CSS_ACTIVE_EDITOR : ""), " "));
        add(editorPanelRoot);

        actionBarCollapsed = isActionBarCollapsed();

        // Document name/project info line, same component the main editor uses in its header.
        // Editability comes from our own action handler rather than from the (possibly editable)
        // enclosing page.
        documentNamePanel = new DocumentNamePanel("documentNamePanel", getModel(),
                this::isEditable);
        editorPanelRoot.add(documentNamePanel);

        // Warns that the editor on screen is not the configured one, because the configured editor
        // cannot display this document's format. Reads through a model rather than being toggled at
        // resolution time: the resolution happens later (loadDocumentIntoEditor) and repeats on
        // every document switch.
        var substitutedEditor = new WebMarkupContainer(MID_SUBSTITUTED_EDITOR);
        substitutedEditor.setOutputMarkupPlaceholderTag(true);
        substitutedEditor.add(visibleWhen(() -> substituteEditorFactoryName != null));
        substitutedEditor.add(AttributeModifier.replace("title",
                LambdaModel.of(() -> substituteEditorFactoryName == null ? ""
                        : "The configured editor (" + substituteEditorFactoryName
                                + ") cannot display "
                                + "this document's format, so the most suitable editor is used "
                                + "instead.")));
        editorPanelRoot.add(substitutedEditor);

        // Host-specific status badges (e.g. the curation readiness badge), shown next to the
        // document name. The default is an empty placeholder.
        // Output markup id is forced here rather than left to the hosts: the badges are repainted
        // on a document state change, and the default is a plain placeholder that no host has any
        // reason to give an id of its own.
        editorPanelRoot.add(createDocumentStatusBadges(MID_DOCUMENT_STATUS_BADGES, getModel())
                .setOutputMarkupPlaceholderTag(true));

        if (getModelObject().getPagingStrategy() == null) {
            getModelObject().setPagingStrategy(new NoPagingStrategy());
        }
        editorPanelRoot.add(getModelObject().getPagingStrategy()
                .createPositionLabel(MID_NUMBER_OF_PAGES, getModel()));

        // The action bar can be collapsed via the toggle in the card header, mirroring the main
        // editor. Its children live inside this container so a single class swap hides them all at
        // once.
        actionBar = new WebMarkupContainer("actionBar");
        actionBar.setOutputMarkupId(true);
        actionBar.add(AttributeModifier.append("class",
                LambdaModel.of(() -> actionBarCollapsed ? " visually-hidden" : "")));
        editorPanelRoot.add(actionBar);

        actionBarToggle = new LambdaAjaxLink("toggleActionBar", this::actionToggleActionBar);
        actionBarToggle.add(new Icon("toggleActionBarIcon",
                LambdaModel.of(() -> actionBarCollapsed ? chevron_down_s : chevron_up_s)));
        actionBarToggle.setOutputMarkupId(true);
        editorPanelRoot.add(actionBarToggle);

        // Closing is per-editor rather than "remove the second pane", so the user closes whichever
        // pane they are done with. Asking the workspace rather than counting panes here keeps the
        // "never leave the page without a document" invariant in one place - see canCloseEditor().
        closeEditor = new LambdaAjaxLink(MID_CLOSE_EDITOR, this::actionCloseEditor);
        closeEditor.setOutputMarkupPlaceholderTag(true);
        closeEditor.add(visibleWhen(this::isCloseable));
        editorPanelRoot.add(closeEditor);

        // Host-provided action bar groups, rendered as siblings of our own group.
        actionBar.add(createActionBarGroups(MID_ACTION_BAR_GROUPS));

        // Host-provided action bar items, rendered before and after the navigation buttons *within*
        // our own group. Both slots sit inside a wicket:container, so whatever the host returns
        // becomes a direct flex child of the action bar group - see the markup.
        var itemsBefore = createActionBarItemsBefore(MID_ACTION_BAR_ITEMS_BEFORE);
        var itemsAfter = createActionBarItemsAfter(MID_ACTION_BAR_ITEMS_AFTER);

        var ownGroup = new WebMarkupContainer(MID_OWN_ACTION_BAR_GROUP);
        ownGroup.setOutputMarkupPlaceholderTag(true);
        ownGroup.add(
                visibleWhen(() -> hasVisibleContent(itemsBefore) || hasVisibleContent(itemsAfter)));
        actionBar.add(ownGroup);
        ownGroup.add(itemsBefore);
        ownGroup.add(itemsAfter);

        actionBarItems = new ActionBar(MID_ACTION_BAR_ITEMS, this);
        actionBarItems.setOutputMarkupId(true);
        actionBar.add(actionBarItems);

        editorContainer = new WebMarkupContainer("editorContainer");
        editorContainer.setOutputMarkupId(true);
        editorContainer.add(new EmptyPanel(MID_EDITOR).setOutputMarkupId(true));

        // A pane with no document renders an EmptyPanel, which is blank. Say what to do instead.
        var noDocumentPrompt = new WebMarkupContainer(MID_NO_DOCUMENT_PROMPT);
        noDocumentPrompt.setOutputMarkupPlaceholderTag(true);
        noDocumentPrompt.add(visibleWhen(() -> getModelObject().getDocument() == null));
        noDocumentPrompt
                .add(new LambdaAjaxLink(MID_OPEN_DOCUMENT_FROM_PROMPT, this::actionOpenDocument));
        editorContainer.add(noDocumentPrompt);

        editorPanelRoot.add(editorContainer);
    }

    @Override
    protected void onInitialize()
    {
        super.onInitialize();

        var state = getModelObject();

        // If the caller pre-selected a document on the state, show it right away instead of an
        // empty viewer.
        var initialDocument = state.getDocument();
        if (isInitialDocumentLoadedByPanel() && initialDocument != null
                && state.getProject() != null) {
            // Keep the full accessible-documents list on the state so the position label reads
            // "[doc i / n]" and previous/next navigation works from the initial document onwards.
            state.setDocument(initialDocument, listAccessibleDocuments());
            try {
                loadDocument();
            }
            catch (IOException e) {
                // Fall back to the empty viewer - the user can pick a document manually.
                LOG.error("Unable to load initial document [{}]", initialDocument, e);
                unloadDocument();
            }
        }
    }

    /**
     * @return whether the given action bar item slot was actually filled by the host. A bare
     *         {@link WebMarkupContainer} is the "nothing here" default returned by
     *         {@link #createActionBarItemsBefore}/{@link #createActionBarItemsAfter}.
     */
    private static boolean hasVisibleContent(Component aComponent)
    {
        if (aComponent == null || WebMarkupContainer.class.equals(aComponent.getClass())) {
            return false;
        }

        // Force visibility recalculation
        aComponent.configure();

        return aComponent.isVisible();
    }

    /**
     * Create host-specific action bar groups, rendered as siblings of the panel's own group.
     * <p>
     * Use this - rather than {@link #createActionBarItemsBefore(String)} - for items that bring
     * their own {@code .action-bar-group}, such as an {@code ActionBar} of
     * {@code ActionBarExtension}s. Nesting one group inside another wraps its buttons onto a second
     * row. The default is an empty placeholder.
     *
     * @param aId
     *            the component id to use.
     * @return the component to add to the action bar.
     */
    protected Component createActionBarGroups(String aId)
    {
        return new WebMarkupContainer(aId);
    }

    /**
     * Create the host-specific items shown before the document navigation buttons, e.g. an
     * open-document control. The default is an empty placeholder.
     *
     * @param aId
     *            the component id to use.
     * @return the component to add to the action bar.
     */
    protected Component createActionBarItemsBefore(String aId)
    {
        return new WebMarkupContainer(aId);
    }

    /**
     * Create the host-specific items shown after the document navigation buttons. The default is an
     * empty placeholder.
     *
     * @param aId
     *            the component id to use.
     * @return the component to add to the action bar.
     */
    protected Component createActionBarItemsAfter(String aId)
    {
        return new WebMarkupContainer(aId);
    }

    /**
     * @return whether the panel loads a pre-selected document itself during
     *         {@link #onInitialize()}. Hosts that run their own document-open flow - the annotation
     *         page upgrades and persists the CAS, transitions the document state and publishes open
     *         events - turn this off and drive {@link #createEditorComponent()} themselves. The
     *         default is on, so a panel handed a state with a document just shows it.
     */
    protected boolean isInitialDocumentLoadedByPanel()
    {
        return true;
    }

    /**
     * @return whether the action bar starts out collapsed. Called from the constructor, so hosts
     *         that persist the setting can restore it here. The default is expanded.
     *         <p>
     *         This is a seed value, not a live accessor: the panel keeps its own copy from here on,
     *         so a host whose backing setting can change while the panel is alive has to push the
     *         new value in via {@link #setActionBarCollapsed}.
     */
    protected boolean isActionBarCollapsed()
    {
        return false;
    }

    /**
     * Push a new collapsed state into the panel, for hosts whose backing setting can change while
     * the panel is alive - e.g. because it is persisted per user and project and another page
     * instance wrote it. Does not call {@link #onActionBarCollapsedChanged}: the value is coming
     * *from* the host, so there is nothing to save back.
     *
     * @param aCollapsed
     *            whether the action bar should be collapsed.
     */
    protected void setActionBarCollapsed(boolean aCollapsed)
    {
        actionBarCollapsed = aCollapsed;
    }

    /**
     * Called after the user toggled the action bar, so hosts that persist the setting can save it.
     * Does nothing by default, i.e. the setting lives as long as the panel does.
     *
     * @param aCollapsed
     *            whether the action bar is now collapsed.
     */
    protected void onActionBarCollapsedChanged(boolean aCollapsed)
    {
        // Do nothing by default
    }

    /**
     * Create the host-specific status badges shown next to the document name in the card header,
     * e.g. a curation readiness badge. The default is an empty placeholder.
     *
     * @param aId
     *            the component id to use.
     * @param aState
     *            the panel's state.
     * @return the component to add to the card header.
     */
    protected Component createDocumentStatusBadges(String aId, IModel<AnnotatorState> aState)
    {
        return new WebMarkupContainer(aId);
    }

    @Override
    public boolean isEditor()
    {
        return false;
    }

    /**
     * @return the accessible documents to page through, in the order the host wants them offered.
     *         <p>
     *         Accessibility is a question about the data owner <b>and</b> the session owner: a
     *         document locked (<code>IGNORE</code>) for the data owner is not theirs to page
     *         through, while a session owner viewing someone else's annotations is a supported case
     *         that must not be filtered by their own flags. Both panes of that question live in
     *         {@link DocumentService#listAccessibleDocuments}, so ask it there rather than listing
     *         all documents and filtering afterwards.
     */
    public List<SourceDocument> listAccessibleDocuments()
    {
        var state = getModelObject();
        var project = state.getProject();
        var dataOwner = state.getUser();
        if (project == null || dataOwner == null) {
            return emptyList();
        }

        return documentService
                .listAccessibleDocuments(project, dataOwner, userRepository.getCurrentUser())
                .stream() //
                .map(AnnotationDocument::getDocument) //
                .collect(toList());
    }

    /**
     * Read the CAS to show for the given document.
     * <p>
     * The mode follows {@link #isEditor()} rather than being an override, so an editable panel
     * cannot accidentally end up on the read-only path (which would silently disable
     * concurrent-edit detection, because the read-only mode never establishes a timestamp).
     *
     * @param aDocument
     *            the document to read.
     * @return the CAS to render.
     * @throws IOException
     *             if the CAS cannot be read.
     */
    protected CAS readCas(SourceDocument aDocument) throws IOException
    {
        var state = getModelObject();
        var set = AnnotationSet.forUser(state.getUser());

        if (!isEditor()) {
            // Read-only: SHARED_READ_ONLY_ACCESS never writes the CAS file (so it does not bump
            // the optimistic-locking timestamp), does not take the exclusive pool lock used by the
            // main editor, and for documents the user has never annotated it returns a transient
            // CAS that is never persisted. This mode requires AUTO_CAS_UPGRADE (upgrade happens
            // in-memory only).
            //
            // Drop any timestamp held on the state: this mode does not establish one, so keeping
            // an older one would leave a baseline that no longer corresponds to a read we made.
            // A later exclusive read would then verify against it and either miss a concurrent
            // change or report one that did not happen.
            state.clearAnnotationDocumentTimestamp();
            return documentService.readAnnotationCas(aDocument, set, AUTO_CAS_UPGRADE,
                    SHARED_READ_ONLY_ACCESS);
        }

        if (state.getAnnotationDocumentTimestamp().isEmpty()) {
            // First exclusive read for this document - or the first after a read-only read cleared
            // the baseline. Establish one now, so concurrent-modification detection is armed from
            // here on rather than only after the first write.
            bumpAnnotationCasTimestamp(state);
        }
        else if (isEditable()) {
            // We hold a baseline from an earlier exclusive read - use it to detect concurrent
            // access
            documentService
                    .verifyAnnotationCasTimestamp(aDocument, set,
                            state.getAnnotationDocumentTimestamp().get(), "reading the editor CAS")
                    .ifPresent(state::setAnnotationDocumentTimestamp);
        }

        return documentService.readAnnotationCas(aDocument, set);
    }

    /**
     * Fail closed if this editor does not accept mutations, either because the host is a viewer or
     * because this user may not edit this document right now.
     */
    @Override
    public void ensureIsEditable() throws NotEditableException
    {
        if (!isEditor()) {
            throw new NotEditableException("This editor is read-only.");
        }

        if (getModelObject().getDocument() == null) {
            throw new NotEditableException("No document selected");
        }

        var reason = notEditableReason.getObject();
        if (reason != null) {
            throw new NotEditableException(reason);
        }
    }

    /**
     * Discard the cached editability verdict so that the next {@link #isEditable()} or
     * {@link #ensureIsEditable()} re-evaluates it.
     * <p>
     * Call this after changing anything the verdict depends on - in particular after a source
     * document state transition, since whether a curator may edit depends on the document having
     * reached a curation state.
     */
    public void clearIsEditableCache()
    {
        notEditableReason.detach();
    }

    private String loadNotEditableReason()
    {
        var state = getModelObject();

        if (state.getDocument() == null) {
            return "No document selected";
        }

        // Check against the latest document state, the one from the state may be stale
        SourceDocument document;
        try {
            document = documentService.getSourceDocument(state.getDocument().getProject().getId(),
                    state.getDocument().getId());
        }
        catch (NoResultException e) {
            return "Document no longer exists";
        }

        try {
            documentAccess.assertCanEditAnnotationDocument(userRepository.getSessionOwner(),
                    document, state.getUser().getUsername());
            return null;
        }
        catch (AccessDeniedException e) {
            return e.getMessage();
        }
    }

    @Override
    public void detachModels()
    {
        super.detachModels();
        notEditableReason.detach();
    }

    /**
     * Called after a document has been loaded into the editor and the standard components have been
     * scheduled for repaint, so hosts can refresh their own action bar items and run follow-up
     * scripts.
     *
     * @param aTarget
     *            the AJAX target.
     */
    protected void onDocumentLoaded(AjaxRequestTarget aTarget)
    {
        // Do nothing by default
    }

    private void actionToggleActionBar(AjaxRequestTarget aTarget)
    {
        actionBarCollapsed = !actionBarCollapsed;

        onActionBarCollapsedChanged(actionBarCollapsed);

        aTarget.add(actionBar, actionBarToggle);
    }

    /**
     * @return whether this pane offers a close button. Only a workspace that can host several
     *         editors can answer this - a single-editor workspace has nothing to close, so the
     *         button stays hidden there rather than each host having to suppress it.
     */
    protected boolean isCloseable()
    {
        return getDocumentEditorManager().canCloseEditor(this);
    }

    /**
     * Repaint the close button when a pane appears or disappears, or when one is filled: whether
     * <i>this</i> pane may be closed depends on how many OTHER panes hold a document, so the answer
     * changes without anything happening to this pane at all.
     * <p>
     * The same two events also decide whether this pane is marked as the active one, which is
     * handled by swapping a class rather than by repainting - see
     * {@link #updateActiveEditorMarker}.
     * <p>
     * ⚠️ Overriding {@code onEvent} rather than using {@code @OnEvent}: the button is invisible in
     * exactly the case that matters (it hid itself while it was the last editor with a document),
     * and {@code @OnEvent} does not deliver to invisible components.
     */
    @Override
    public void onEvent(IEvent<?> aEvent)
    {
        super.onEvent(aEvent);

        if (aEvent.getPayload() instanceof EditorSetChangedEvent event
                && event.getRequestHandler() != null) {
            event.getRequestHandler().add(closeEditor);
            updateActiveEditorMarker(event.getRequestHandler());
        }

        if (aEvent.getPayload() instanceof ActiveEditorChangedEvent event
                && event.getRequestHandler() != null) {
            updateActiveEditorMarker(event.getRequestHandler());
        }

        if (aEvent.getPayload() instanceof DefaultLayerChangedEvent event) {
            onDefaultLayerChanged(event);
        }

        if (aEvent.getPayload() instanceof AnchoringModeChangedEvent event) {
            onAnchoringModeChanged(event);
        }
    }

    /**
     * Adopt an anchoring mode chosen elsewhere on the page.
     * <p>
     * Like the layer, the anchoring mode is persisted per user and project, so it describes the
     * working session rather than one pane. An editor whose default layer is the one that changed
     * has to pick the new mode up, or drawing in it would still use the old one.
     *
     * @param aEvent
     *            the change.
     */
    private void onAnchoringModeChanged(AnchoringModeChangedEvent aEvent)
    {
        var state = getModelObject();

        if (!Objects.equals(state.getDefaultAnnotationLayer(), aEvent.getLayer())) {
            return;
        }

        state.syncAnchoringModeToDefaultLayer(aEvent.getPrefs());
    }

    /**
     * Adopt a layer chosen elsewhere on the page.
     * <p>
     * The layer selection belongs to the user's working session rather than to one pane - every
     * editor seeds it from the same user preference on load - so a choice made while another pane
     * was active applies here too. Without this the states diverge as soon as there are two panes:
     * drawing a span uses the state of the editor drawn in, not the active one, so the annotation
     * would land on whatever layer this pane happened to be left on.
     * <p>
     * The anchoring mode is remembered per layer, so it has to follow the layer - otherwise drawing
     * here would keep using the mode of the layer this pane was on before.
     *
     * @param aEvent
     *            the change.
     */
    private void onDefaultLayerChanged(DefaultLayerChangedEvent aEvent)
    {
        var layer = aEvent.getLayer();
        if (layer == null) {
            return;
        }

        var state = getModelObject();

        // Not every pane can honour it - one showing a document whose project or configuration does
        // not offer this layer must keep the layer it has.
        if (!state.getSelectableLayers().contains(layer)) {
            return;
        }

        state.setDefaultAnnotationLayer(layer);
        state.setSelectedAnnotationLayer(layer);
        state.syncAnchoringModeToDefaultLayer(aEvent.getAnchoringPrefs());
    }

    private void updateActiveEditorMarker(AjaxRequestTarget aTarget)
    {
        aTarget.appendJavaScript(wrapInTryCatch(format(
                "document.getElementById('%s')?.classList.toggle('%s', %s);",
                editorPanelRoot.getMarkupId(), CSS_ACTIVE_EDITOR, isActiveEditorIndicated())));
    }

    /**
     * @return whether this pane should show that it is the one the user is working in. Asks the
     *         manager rather than comparing against the active context here: whether the marker is
     *         shown at all depends on how many editors the host has, which is the manager's
     *         business - the same split of responsibilities as {@link #isCloseable()}.
     */
    protected boolean isActiveEditorIndicated()
    {
        return getDocumentEditorManager().isMarkedActiveEditor(this);
    }

    private void actionCloseEditor(AjaxRequestTarget aTarget)
    {
        getDocumentEditorManager().closeEditor(aTarget, this);
    }

    /**
     * Show the open-document dialog for <b>this</b> pane. Mind that the dialog is a single
     * component in the page footer: it is told which pane asked, so that the document lands where
     * the user pressed the button rather than in whichever pane happens to be active.
     */
    protected void actionOpenDocument(AjaxRequestTarget aTarget)
    {
        getModelObject().clearSelection();

        findParent(ApplicationPageBase.class).getFooterItems().getObject().stream()
                .filter(component -> component instanceof OpenDocumentDialog)
                .map(component -> (OpenDocumentDialog) component).findFirst()
                .ifPresent(dialog -> dialog.show(aTarget, this));
    }

    /**
     * Open the given document in the editor, scroll to the given location. Optionally highlight
     * additional ranges during the scroll.
     */
    @Override
    public void actionShowSelectedDocument(AjaxRequestTarget aTarget, SourceDocument aDocument,
            AnnotationSet aDataOwner, Range aRange, List<VRange> aAdditionalPingRanges)
        throws IOException, AnnotationException
    {
        Validate.notNull(aDataOwner, "Data owner must be specified");

        // A null document means "wherever we are" - callers default to the context's own document,
        // which is itself null until the first one has been loaded.
        var state = getAnnotatorState();
        var switched = (aDocument != null && !aDocument.equals(state.getDocument()))
                || (state.getDocument() != null && !aDataOwner.equals(state.getDataOwner()));
        if (switched) {
            openDocument(aTarget, aDocument != null ? aDocument : state.getDocument(), aDataOwner);
        }

        if (getAnnotatorState().getDocument() == null) {
            return;
        }

        if (!isUndefined(aRange)) {
            actionJump(aTarget, aRange.getBegin(), aRange.getEnd(), aAdditionalPingRanges);
        }

        if (switched) {
            // A switch forces a refresh: the editor now shows different content, so the client
            // cannot simply scroll to the new location.
            actionRefreshDocument(aTarget);
        }
    }

    private void openDocument(AjaxRequestTarget aTarget, SourceDocument aDocument,
            AnnotationSet aDataOwner)
        throws AnnotationException
    {
        Validate.notNull(aDocument, "Document must be specified");
        Validate.notNull(aDataOwner, "Data owner must be specified");

        var state = getModelObject();
        var previousUser = state.getUser();

        // getUserOrCurationUser() rather than get(): the curation user has no database row - it is
        // synthesized - so a plain lookup would come up empty for it.
        var user = userRepository.getUserOrCurationUser(aDataOwner.id());
        if (user == null) {
            throw new IllegalArgumentException("No such user [" + aDataOwner + "]");
        }

        // The accessible documents depend on the data owner, so the owner has to be in place before
        // the document can be validated. Should that validation fail, the owner is put back: an
        // editor left holding a new owner and the old document would write one person's CAS under
        // another person's name.
        state.setUser(user);
        try {
            // Whether this document may be shown for this owner at all. Checked here rather than
            // only on the routes into the panel, so that every caller - URL fragment, open dialog,
            // sidebars - is covered by the same decision. The message is deliberately vague: it
            // must not reveal whether the document exists to somebody not allowed to know.
            try {
                documentAccess.assertCanViewAnnotationDocument(userRepository.getCurrentUser(),
                        aDocument, aDataOwner.id());
            }
            catch (AccessDeniedException e) {
                LOG.debug("Access denied opening [{}] for [{}]: {}", aDocument, aDataOwner,
                        e.getMessage());
                throw new AnnotationException(
                        "Requested document does not exist or you have no permissions to access it.");
            }

            var documents = listAccessibleDocuments();
            if (!documents.contains(aDocument)) {
                throw new AnnotationException("Document [" + aDocument.getName()
                        + "] cannot be shown in this editor for [" + aDataOwner + "].");
            }

            LOG.trace("Opening [{}] for [{}] (was [{}])", aDocument, aDataOwner, previousUser);

            // Keep the full list on the state so the position label stays "[doc i / n]"-consistent.
            state.setDocument(aDocument, documents);
        }
        catch (RuntimeException | AnnotationException e) {
            state.setUser(previousUser);
            throw e;
        }

        actionLoadDocument(aTarget);
    }

    /**
     * Load the document currently set on our state model into a fresh editor. Invoked after the
     * document on the state has changed - e.g. by the previous/next navigation, or by the host. The
     * editor type is resolved automatically from the document format.
     *
     * @param aTarget
     *            the AJAX target (optional).
     */
    @Override
    public void actionLoadDocument(AjaxRequestTarget aTarget, int aFocus)
    {
        try {
            loadDocument(aFocus);

            send(getPage(), BREADTH, new EditorContentReplacedEvent(getModelObject(), aTarget));

            refreshAfterDocumentChange(aTarget);

            onDocumentLoaded(aTarget);
        }
        catch (IOException e) {
            error("Unable to load document: " + e.getMessage());

            unloadDocument();
            refreshAfterDocumentChange(aTarget);

            // The previous document is gone all the same - whatever followed it must hear of it
            if (aTarget != null) {
                aTarget.addChildren(getPage(), IFeedback.class);
                send(getPage(), BREADTH, new EditorContentReplacedEvent(getModelObject(), aTarget));
            }
        }
    }

    /**
     * Repaint everything that depends on which document is loaded.
     * <p>
     * Scoped to this pane. A host that overrides {@link #actionLoadDocument} to run its own load
     * flow must still call this, or its pane keeps showing the previous document. Repainting a
     * shared ancestor instead also works, but at the price of repainting every other pane with it.
     */
    protected void refreshAfterDocumentChange(AjaxRequestTarget aTarget)
    {
        if (aTarget == null) {
            return;
        }

        aTarget.add(editorContainer, documentNamePanel, actionBarItems,
                actionBar.get(MID_OWN_ACTION_BAR_GROUP), editorPanelRoot.get(MID_NUMBER_OF_PAGES),
                editorPanelRoot.get(MID_SUBSTITUTED_EDITOR));
    }

    /**
     * Repaint everything in this pane that depends on the document <b>state</b> - which is almost
     * everything {@link #refreshAfterDocumentChange} repaints, plus the status badges, because
     * editability gates the action bar items, the lock icon and the editor itself alike.
     * <p>
     * The cached editability verdict is dropped first. Without that, everything repainted below
     * would re-render against the verdict from before the transition that prompted this call - the
     * caller has just changed the state, but within this request nothing has re-read it yet.
     */
    @Override
    public void refreshAfterDocumentStateChange(AjaxRequestTarget aTarget)
    {
        clearIsEditableCache();

        if (aTarget == null) {
            return;
        }

        // Everything chrome-side that reads editability: the action bar items (undo appears and
        // disappears outright), the lock icon on the name panel, the status badges.
        aTarget.add(documentNamePanel, actionBarItems, actionBar.get(MID_OWN_ACTION_BAR_GROUP),
                editorPanelRoot.get(MID_DOCUMENT_STATUS_BADGES));

        // The editor re-renders in place rather than being replaced: the document shown is
        // unchanged, only whether it may be edited. Repainting editorContainer instead would
        // remount the editor the user is working in, which is the cost this method exists to avoid.
        actionRefreshDocument(aTarget);

        // Everything outside this pane that reads editability - the sidebars above all - is the
        // page's, not ours. Announce the transition instead of reaching across to repaint it, so
        // the panes stay independent and consumers can scope the update to the editor it came from.
        send(getPage(), BREADTH, new DocumentStateChangedInEditorEvent(getModelObject(), aTarget));
    }

    @Override
    public void actionUnloadDocument(AjaxRequestTarget aTarget)
    {
        unloadDocument();
        clearIsEditableCache();
        refreshAfterDocumentChange(aTarget);

        if (aTarget != null) {
            send(getPage(), BREADTH, new EditorContentReplacedEvent(getModelObject(), aTarget));
        }

        // This pane no longer holds a document, which changes whether the OTHER panes may be
        // closed and what the URL fragment describes - both follow the editor set.
        send(getPage(), BREADTH, new EditorSetChangedEvent(aTarget));
    }

    private void unloadDocument()
    {
        getModelObject().setDocument(null, emptyList());
        createEditorComponent();
    }

    private static String contentKey(SourceDocument aDocument, AnnotationSet aDataOwner)
    {
        if (aDocument == null || aDocument.getId() == null || aDataOwner == null) {
            return null;
        }

        return aDocument.getId() + "/" + aDataOwner.id();
    }

    /**
     * @return whether this editor has already been built for the document <b>and</b> data owner its
     *         state points at. False when no document is set, and while a switch is in flight - the
     *         state names the new content but the editor still shows the old one.
     */
    public boolean isShowingLoadedDocument()
    {
        var key = contentKey(getModelObject().getDocument(), getModelObject().getDataOwner());
        return key != null && key.equals(loadedContentKey);
    }

    private void loadDocument() throws IOException
    {
        loadDocument(0);
    }

    /**
     * Load the document on our state into a fresh editor and page to the given unit.
     *
     * @param aFocus
     *            the unit index to show, aligned to the <b>top</b> of the window.
     */
    private void loadDocument(int aFocus) throws IOException
    {
        var state = getModelObject();

        createEditorComponent();

        if (state.getDocument() == null) {
            return;
        }

        // Reset before reading the CAS, not after. reset() clears the annotation-document
        // timestamp, and readCas() uses that timestamp to detect concurrent modification. Reading
        // first means the freshly loaded document is verified against the timestamp left over from
        // the document being navigated away from, which is not a meaningful comparison.
        state.reset();

        // The timestamp cleared by the reset is re-established by readCas() below, which owns that
        // invariant: it knows which access mode it used and therefore whether a baseline exists.
        var cas = getEditorCas();

        state.getPagingStrategy().recalculatePage(state, cas);
        state.moveToUnit(cas, aFocus, TOP);
    }

    public void createEditorComponent()
    {
        var state = getModelObject();
        var document = state.getDocument();

        loadedContentKey = contentKey(document, state.getDataOwner());

        Component newEditor;
        if (document == null) {
            editor = null;
            substituteEditorFactoryName = null;
            state.setPagingStrategy(new NoPagingStrategy());
            newEditor = new EmptyPanel(MID_EDITOR);
        }
        else {
            var factory = resolveEditorFactory(document);
            state.setEditorFactoryId(factory.getBeanName());

            editor = factory.create(MID_EDITOR, getModel(), getDocumentEditorManager(), this,
                    this::getEditorCas);

            // Let the editor configure the paging strategy
            factory.initState(state);

            newEditor = editor;
        }

        newEditor.setOutputMarkupId(true);
        editorContainer.addOrReplace(newEditor);

        // The position label is bound to the (possibly new) paging strategy, so recreate it.
        var positionLabel = state.getPagingStrategy().createPositionLabel(MID_NUMBER_OF_PAGES,
                getModel());
        positionLabel.add(visibleWhen(() -> getModelObject().getDocument() != null));
        editorPanelRoot.addOrReplace(positionLabel);
    }

    /**
     * Pick the editor to show the given document in. Hosts that always show one particular editor
     * override this and return their factory directly.
     */
    protected AnnotationEditorFactory resolveEditorFactory(SourceDocument aDocument)
    {
        var project = aDocument.getProject();
        var format = aDocument.getFormat();

        var configuredId = preferencesService
                .loadDefaultTraitsForProject(KEY_ANNOTATION_EDITOR_MANAGER_PREFS, project)
                .getDefaultEditor();

        if (configuredId == null) {
            configuredId = getModelObject().getPreferences().getEditor();
        }

        var factory = editorRegistry.getEditorFactory(project, format, configuredId);

        var configured = editorRegistry.getEditorFactory(configuredId);
        if (configured != null && configured != factory) {
            substituteEditorFactoryName = configured.getDisplayName();
            LOG.info(
                    "Configured editor [{}] cannot display format [{}] of document [{}] "
                            + "- using [{}]",
                    configuredId, format, aDocument.getName(), factory.getBeanName());
        }
        else {
            substituteEditorFactoryName = null;
        }

        return factory;
    }

    /**
     * The display name of the editor factory used for the editor if it is not the configured editor
     * (i.e. if the configured editor factory does not support the current document and we had to
     * fall back to another one).
     */
    public String getSubstitutedEditorName()
    {
        return substituteEditorFactoryName;
    }

    @Override
    public Optional<String> getViewportSyncClientId()
    {
        return editor != null ? editor.getViewportSyncClientId() : Optional.empty();
    }

    public AnnotationEditorBase getEditor()
    {
        return editor;
    }

    public Component getActionBarItems()
    {
        return actionBarItems;
    }

    public void refreshActionBarItems()
    {
        actionBarItems.refresh();
    }

    public Optional<ContextMenuLookup> getContextMenuLookup()
    {
        return editor != null ? editor.getContextMenuLookup() : Optional.empty();
    }

    public Component getPositionLabel()
    {
        return editorPanelRoot.get(MID_NUMBER_OF_PAGES);
    }

    /**
     * Pick up changed annotation preferences. Each editor on a page carries its own
     * {@link AnnotatorState} with its own snapshot of the preferences, and the dialog only updates
     * the state it was opened on - so unless we reload here, this panel would keep rendering with
     * the settings that were current when it was constructed.
     */
    @OnEvent
    public void onAnnotationPreferencesChanged(AnnotationPreferencesChangedEvent aEvent)
    {
        var state = getModelObject();

        // Preferences are per user and project - ignore changes for a project we are not showing.
        if (state.getProject() == null || !state.getProject().equals(aEvent.getProject())) {
            return;
        }

        try {
            userPreferencesService.loadPreferences(state, aEvent.getSessionOwnerName());
        }
        catch (IOException e) {
            LOG.error("Unable to reload annotation preferences", e);
            return;
        }

        var target = aEvent.getRequestHandler();
        if (target == null) {
            return;
        }

        if (state.getDocument() == null) {
            return;
        }

        if (aEvent.isEditorStructureAffected()) {
            actionLoadDocument(target);
        }
        else {
            // Everything that changed is decided while rendering, so paint the existing editor
            // again and leave the user where they were.
            actionRefreshDocument(target);
        }
    }

    /**
     * Re-render this editor when the selection has changed, so the selection highlight updates.
     */
    @OnEvent
    public void onSelectionChangedEvent(SelectionChangedEvent aEvent)
    {
        // Only react to selection changes in our own editor, not in the other editors on the page
        // (e.g. the reference-document viewer) even if they show the same document (#6146).
        if (!aEvent.isFor(getModelObject())) {
            return;
        }

        actionRefreshDocument(aEvent.getRequestHandler());
    }

    /**
     * Re-render this editor when an annotation has been created or deleted, assuming that this
     * might have changed some feature shown on screen.
     * <p>
     * NOTE: This is a backend event, so it does not know about editors and cannot be matched with
     * {@code isFor(state)} the way the editor-bound events above can. We compare the coordinates
     * the event does carry - project, document and document owner - against our own.
     */
    @OnEvent
    public void onAnnotationEvent(AnnotationEvent aEvent)
    {
        if (!isForThisEditor(aEvent.getProject(), aEvent.getDocument(),
                aEvent.getDocumentOwner())) {
            return;
        }

        aEvent.getRequestTarget().ifPresent(this::actionRefreshDocument);
    }

    /**
     * Re-render this editor when a feature value has changed, assuming that this might have changed
     * some feature shown on screen. Backend event - see {@link #onAnnotationEvent}.
     */
    @OnEvent
    public void onFeatureValueUpdatedEvent(FeatureValueUpdatedEvent aEvent)
    {
        if (!isForThisEditor(aEvent.getProject(), aEvent.getDocument(),
                aEvent.getDocumentOwner())) {
            return;
        }

        actionRefreshDocument(aEvent.getRequestTarget().orElse(null));
    }

    /**
     * Re-render this editor when its view has changed, e.g. due to paging, and repaint the position
     * label that reports the new position.
     */
    @OnEvent
    public void onViewStateChanged(AnnotatorViewportChangedEvent aEvent)
    {
        // Only react to viewport changes in our own editor, not in the other editors on the page
        // (e.g. the reference-document viewer) even if they show the same document (#6146).
        if (!aEvent.isFor(getModelObject())) {
            return;
        }

        // Partial page updates only need to be triggered if we are in a partial page update request
        if (aEvent.getRequestHandler() == null) {
            return;
        }

        try {
            aEvent.getRequestHandler().add(getPositionLabel());
        }
        catch (IllegalStateException e) {
            // Ignore IllegalStateException if rendering of the page has already progressed so far
            // that no new components can be added. We hope the caller will know what they are doing
            // when they invoke this method so late in the render cycle and trigger a page-reload
            // themselves.
        }

        actionRefreshDocument(aEvent.getRequestHandler());
    }

    /**
     * Whether a backend event that carries only document coordinates (no editor identity) concerns
     * the document this editor currently shows. Note that two editors on the same page can show the
     * same document for the same data owner - both then legitimately refresh.
     */
    private boolean isForThisEditor(Project aProject, SourceDocument aDocument,
            String aDocumentOwner)
    {
        var state = getModelObject();

        return Objects.equals(state.getProject(), aProject)
                && Objects.equals(state.getDocument(), aDocument)
                && Objects.equals(state.getUser().getUsername(), aDocumentOwner);
    }

    // --- DocumentEditorActionHandler / DiamContext --------------------------------------------

    @Override
    public IModel<AnnotatorState> getStateModel()
    {
        return getModel();
    }

    @Override
    public CAS getEditorCas() throws IOException
    {
        // Resolve the document from the current state rather than through casProvider: the provider
        // is a closure created when the editor component was built, so it would still serve the
        // document that was shown then. Callers may ask for the CAS after the state has moved on
        // but before a new editor has been created (e.g. a page reload restoring the resumption
        // location).
        var document = getModelObject().getDocument();
        if (document == null) {
            throw new IllegalStateException("No document is currently loaded");
        }

        return readCas(document);
    }

    @Override
    public DocumentEditorManager getDocumentEditorManager()
    {
        return manager;
    }

    @Override
    public AnnotationActionHandler getActionHandler()
    {
        return this;
    }

    @Override
    public Selection selectionFor(VID aVid, AnnotationFS aAnnotation)
    {
        return annotationSchemaService.findAdapter(getProject(), aAnnotation).select(aVid,
                aAnnotation);
    }

    @Override
    public void actionRefreshDocument(AjaxRequestTarget aTarget)
    {
        if (editor == null) {
            return;
        }

        try {
            editor.requestRender(aTarget);
        }
        catch (ReplaceHandlerException e) {
            // Let Wicket redirects still work
            throw e;
        }
        catch (Exception e) {
            // The frozen-request case is already handled inside requestRender, which logs and
            // carries on. What reaches us here comes from rendering itself, so the editor may be
            // left inconsistent. For the main editor that warrants a page reload; for a read-only
            // viewer it does not - discarding the page would take the main editor's unsaved state
            // with it.
            LOG.warn("Unable to refresh annotation editor", e);
            if (isEditor()) {
                throw new RestartResponseException(getPage());
            }
        }
    }

    /**
     * @return the annotation detail panel that detail-bound actions of this panel should be
     *         delegated to, if this panel is hosted somewhere that shows one.
     *         <p>
     *         This is a delegation seam, not an ownership claim: the detail panel is created and
     *         owned by the hosting page, and it follows the page's <em>active</em> editor rather
     *         than belonging to any one panel. A panel that returns one here is only saying where
     *         its detail-bound actions should land while it is the active editor.
     *         <p>
     *         The default is empty, which makes the actions below fail closed - a panel used as a
     *         read-only viewer has no detail panel to delegate to.
     */
    protected Optional<AnnotationDetailEditorPanel> getDetailPanel()
    {
        return Optional.empty();
    }

    private AnnotationDetailEditorPanel detailPanel(String aMessage) throws AnnotationException
    {
        return getDetailPanel().orElseThrow(() -> new NotEditableException(aMessage));
    }

    @Override
    public void actionLoadSelectedAnnotationDetails(AjaxRequestTarget aTarget)
        throws IOException, AnnotationException
    {
        // Activating this panel makes it the active editor, so the detail panel - which follows the
        // active editor - resolves its state and CAS to this panel before we load into it.
        activate(aTarget);

        var detailPanel = getDetailPanel();
        if (detailPanel.isPresent()) {
            detailPanel.get().actionLoadSelectionDetails(aTarget);
        }

        // Re-render document so selection highlight shows
        actionRefreshDocument(aTarget);
    }

    @Override
    public void actionDelete(AjaxRequestTarget aTarget) throws IOException, AnnotationException
    {
        ensureIsEditable();
        detailPanel("This editor cannot delete annotations.").actionDelete(aTarget);
    }

    @Override
    public void actionReverse(AjaxRequestTarget aTarget) throws IOException, AnnotationException
    {
        ensureIsEditable();
        detailPanel("This editor cannot reverse annotations.").actionReverse(aTarget);
    }

    @Override
    public void actionFillSlot(AjaxRequestTarget aTarget, int aSlotFillerBegin, int aSlotFillerEnd)
        throws IOException, AnnotationException
    {
        ensureIsEditable();
        detailPanel("This editor cannot fill slots.").actionFillSlot(aTarget, aSlotFillerBegin,
                aSlotFillerEnd);
    }

    @Override
    public void actionFillSlot(AjaxRequestTarget aTarget, VID aExistingSlotFillerId)
        throws IOException, AnnotationException
    {
        ensureIsEditable();
        detailPanel("This editor cannot fill slots.").actionFillSlot(aTarget,
                aExistingSlotFillerId);
    }

    @Override
    public void writeEditorCas() throws IOException, AnnotationException
    {
        writeEditorCas(getEditorCas());
    }

    /**
     * Persist the given CAS as the annotations of this editor's document and data owner, and bump
     * the optimistic-locking timestamp so a concurrent modification by someone else is detected on
     * the next read.
     *
     * @param aCas
     *            the CAS to write.
     * @throws IOException
     *             if the CAS cannot be written.
     * @throws AnnotationException
     *             if this editor does not accept mutations.
     */
    @Override
    public void writeEditorCas(CAS aCas) throws IOException, AnnotationException
    {
        ensureIsEditable();

        var state = getModelObject();
        documentService.writeAnnotationCas(aCas, state.getDocument(), state.getUser(),
                EXPLICIT_ANNOTATOR_USER_ACTION);

        bumpAnnotationCasTimestamp(state);
    }

    /**
     * Record the current on-disk timestamp of the annotations on the given state, so that a
     * concurrent modification by someone else can be detected the next time the CAS is read.
     *
     * @param aState
     *            the state to record the timestamp on.
     * @throws IOException
     *             if the timestamp cannot be read.
     */
    public void bumpAnnotationCasTimestamp(AnnotatorState aState) throws IOException
    {
        documentService
                .getAnnotationCasTimestamp(aState.getDocument(),
                        AnnotationSet.forUser(aState.getUser()))
                .ifPresent(aState::setAnnotationDocumentTimestamp);
    }
}
