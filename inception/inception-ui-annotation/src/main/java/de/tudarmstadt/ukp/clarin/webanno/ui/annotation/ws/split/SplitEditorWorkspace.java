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
package de.tudarmstadt.ukp.clarin.webanno.ui.annotation.ws.split;

import static de.tudarmstadt.ukp.inception.support.lambda.LambdaBehavior.visibleWhen;
import static java.util.Collections.unmodifiableList;
import static org.apache.wicket.event.Broadcast.BREADTH;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.Validate;
import org.apache.wicket.Component;
import org.apache.wicket.Page;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.OnDomReadyHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.repeater.RepeatingView;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.danekja.java.util.function.serializable.SerializableFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wicketstuff.event.annotation.OnEvent;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationSet;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.AnnotationPageBase2;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.editor.DocumentEditorPanel;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.editor.PausableSwitch;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.editor.ViewportSyncLink;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.editor.ViewportSyncLink.Obstacle;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.url.EditorUrlParameterStrategy;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.ws.DocumentEditorWorkspace_ImplBase;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.ws.EditorRequest;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotationException;
import de.tudarmstadt.ukp.inception.rendering.editorstate.DocumentEditor;
import de.tudarmstadt.ukp.inception.rendering.editorstate.DocumentEditorManager;
import de.tudarmstadt.ukp.inception.rendering.selection.ActiveEditorChangedEvent;
import de.tudarmstadt.ukp.inception.rendering.selection.AnnotatorViewportChangedEvent;
import de.tudarmstadt.ukp.inception.rendering.selection.EditorContentReplacedEvent;
import de.tudarmstadt.ukp.inception.rendering.selection.EditorSetChangedEvent;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VRange;
import de.tudarmstadt.ukp.inception.support.uima.Range;
import de.tudarmstadt.ukp.inception.workload.model.WorkloadManagementService;

/**
 * Hosts up to {@code maxEditors} {@link DocumentEditorPanel}s side by side.
 *
 * @see DocumentEditorManager
 */
public class SplitEditorWorkspace
    extends DocumentEditorWorkspace_ImplBase
{
    private static final long serialVersionUID = -3092705629029946397L;

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final String MID_EDITOR_PANELS = "editorPanels";
    private static final String MID_EDITOR_PANEL_CONTAINER = "editorPanelContainer";

    private @SpringBean WorkloadManagementService workloadManagementService;

    private final int maxEditors;
    private final LoadableDetachableModel<Boolean> isDocumentRandomAccessAllowed = //
            LoadableDetachableModel.of(this::loadIsDocumentRandomAccessAllowed);
    private final SerializableFunction<String, DocumentEditorPanel> editorPanelFactory;
    private final EditorUrlParameterStrategy urlParameterStrategy;

    private final WebMarkupContainer editorPanelContainer;
    private final RepeatingView editorPanels;

    private DocumentEditor activeEditor;

    private final ViewportSyncLink scrollSyncLink = new ViewportSyncLink(() -> getScrollSyncPane(0),
            () -> getScrollSyncPane(1));

    /** Whether the user wants the two panes to scroll together. */
    private final PausableSwitch<Obstacle> scrollSync = new PausableSwitch<>(
            () -> scrollSyncLink.getObstacle());

    /**
     * @param aUrlParameterStrategy
     *            how the editors hosted here describe themselves in the URL.
     */
    public SplitEditorWorkspace(String aId, int aMaxEditors,
            SerializableFunction<String, DocumentEditorPanel> aEditorPanelFactory,
            EditorUrlParameterStrategy aUrlParameterStrategy)
    {
        super(aId);

        if (aMaxEditors < 1) {
            throw new IllegalArgumentException(
                    "A workspace must be able to host at least one editor, but the cap was ["
                            + aMaxEditors + "]");
        }

        LOG.debug("Installing split editor workspace with a cap of [{}] editors", aMaxEditors);

        maxEditors = aMaxEditors;
        editorPanelFactory = aEditorPanelFactory;
        urlParameterStrategy = aUrlParameterStrategy;

        setOutputMarkupPlaceholderTag(true);

        add(visibleWhen(this::isShowingEditors));

        // A RepeatingView rather than a ListView: panels must be attached to the component tree
        // the moment they are created, because actionLoadDocument() calls into the panel (e.g.
        // refreshActionBarItems()) before the next render. A ListView only attaches its children
        // in populateItem() during render, which leaves the panel page-less until then and fails
        // with "No Page found for component".
        editorPanels = new RepeatingView(MID_EDITOR_PANELS);

        editorPanelContainer = new WebMarkupContainer(MID_EDITOR_PANEL_CONTAINER);
        editorPanelContainer.setOutputMarkupId(true);
        editorPanelContainer.add(editorPanels);
        add(editorPanelContainer);
    }

    /**
     * @return the maximum number of editors this workspace may host. A user whom the workload
     *         manager does not let open documents in any order - it hands out one document after
     *         the other - gets a single editor. The cap is enforced here rather than where the
     *         split is offered, because a URL fragment can request editors too.
     */
    public int getMaxEditors()
    {
        // Not attached to a project page (yet): nothing to decide by, and nothing to cache
        if (getProject() == null) {
            return 1;
        }

        return isDocumentRandomAccessAllowed.getObject() ? maxEditors : 1;
    }

    private Project getProject()
    {
        var page = findParent(AnnotationPageBase2.class);
        return page != null ? page.getProject() : null;
    }

    private boolean loadIsDocumentRandomAccessAllowed()
    {
        var project = getProject();
        return workloadManagementService.getWorkloadManagerExtension(project)
                .isDocumentRandomAccessAllowed(project);
    }

    @Override
    protected void onDetach()
    {
        super.onDetach();
        isDocumentRandomAccessAllowed.detach();
    }

    /**
     * @return the editor panels currently hosted, in display order. Never {@code null}, possibly
     *         empty before the first document has been opened.
     */
    public List<DocumentEditorPanel> getEditorPanels()
    {
        var panels = new ArrayList<DocumentEditorPanel>();
        editorPanels.visitChildren(DocumentEditorPanel.class,
                (aChild, aVisit) -> panels.add((DocumentEditorPanel) aChild));
        return unmodifiableList(panels);
    }

    @Override
    public List<DocumentEditor> getEditors()
    {
        return unmodifiableList(getEditorPanels());
    }

    @Override
    public boolean isShowingEditors()
    {
        return !getEditorPanels().isEmpty();
    }

    /**
     * @return whether another editor may be added, i.e. whether the cap has not been reached.
     */
    public boolean canAddEditor()
    {
        return editorPanels.size() < getMaxEditors();
    }

    /**
     * Add an empty editor pane, <b>leaving the active one where it was</b>.
     * <p>
     * If the workspace had no active editor at all the new editor will be activated.
     *
     * @param aTarget
     *            the AJAX target, so the new pane and its consumers are re-rendered.
     * @return the new pane.
     */
    public DocumentEditorPanel addEmptyEditor(AjaxRequestTarget aTarget)
    {
        var panel = addEditorPanel();

        if (activeEditor == null) {
            setActiveEditor(aTarget, panel);
        }

        if (aTarget != null) {
            aTarget.add(editorPanelContainer);
        }

        fireEditorSetChanged(aTarget);

        return panel;
    }

    /**
     * @return the editors that currently hold a document. An editor added by
     *         {@link #addEmptyEditor} is absent from this list until something is opened in it.
     */
    public List<DocumentEditorPanel> getEditorsWithDocument()
    {
        return getEditorPanels().stream() //
                .filter(panel -> panel.getAnnotatorState().getDocument() != null) //
                .toList();
    }

    /**
     * @param aEditor
     *            the editors in question.
     * @return whether that pane may be closed. Mind that the condition is about <b>documents</b>,
     *         not panes: the page must never be left without an open document, so the last editor
     *         holding one cannot be closed - not even while a second, still-empty pane exists.
     */
    @Override
    public boolean canCloseEditor(DocumentEditor aEditor)
    {
        if (!(aEditor instanceof DocumentEditorPanel aPanel)
                || !getEditorPanels().contains(aPanel)) {
            return false;
        }

        if (editorPanels.size() < 2) {
            // Never offer to close the only pane - that is what leaves the page with nothing.
            return false;
        }

        if (aPanel.getAnnotatorState().getDocument() == null) {
            // An empty pane holds nothing to lose, so it may always go.
            return true;
        }

        return getEditorsWithDocument().size() > 1;
    }

    /**
     * Close a pane, dropping the document it shows.
     *
     * @param aTarget
     *            the AJAX target.
     * @param aEditor
     *            the editor to close.
     */
    @Override
    public void closeEditor(AjaxRequestTarget aTarget, DocumentEditor aEditor)
    {
        if (!canCloseEditor(aEditor)) {
            throw new IllegalStateException(
                    "Cannot close the last editor holding an open document");
        }

        editorPanels.remove((DocumentEditorPanel) aEditor);

        dropActiveEditorIfNotDisplayed(aTarget);

        if (aTarget != null) {
            aTarget.add(editorPanelContainer);
        }

        fireEditorSetChanged(aTarget);
    }

    private void fireEditorSetChanged(AjaxRequestTarget aTarget)
    {
        refreshScrollSync(aTarget);

        var page = findPage();
        if (page != null) {
            page.send(page, BREADTH, new EditorSetChangedEvent(aTarget));
        }
    }

    private DocumentEditorPanel getEditorPanel(int aIndex)
    {
        var panels = getEditorPanels();
        return aIndex < panels.size() ? panels.get(aIndex) : null;
    }

    /**
     * Open the first editor panel, creating it on first use, and make it the active one.
     */
    @Override
    public void openDocumentEditor(AjaxRequestTarget aTarget)
    {
        var panel = getEditorPanel(0);
        if (panel == null) {
            panel = addEditorPanel();
        }

        setActiveEditor(aTarget, panel);
    }

    private DocumentEditorPanel addEditorPanel()
    {
        if (!canAddEditor()) {
            throw new IllegalStateException(
                    "Cannot host more than [" + getMaxEditors() + "] editors");
        }

        var panel = editorPanelFactory.apply(editorPanels.newChildId());
        editorPanels.add(panel);
        return panel;
    }

    @Override
    public Optional<DocumentEditor> getActiveEditor()
    {
        return Optional.ofNullable(activeEditor);
    }

    @Override
    public boolean isMarkedActiveEditor(DocumentEditor aEditor)
    {
        // If there is only a single editor, no need to highlight which one is active
        return aEditor == activeEditor && getEditorPanels().size() > 1;
    }

    @Override
    public void setActiveEditor(AjaxRequestTarget aTarget, DocumentEditor aEditor)
    {
        if (aEditor == activeEditor) {
            return;
        }

        activeEditor = aEditor;

        var page = findPage();
        if (page != null) {
            page.send(page, BREADTH, new ActiveEditorChangedEvent(activeEditor, aTarget));
        }
    }

    @Override
    public boolean hasOpenDocument()
    {
        return !getEditorsWithDocument().isEmpty();
    }

    @Override
    public void dropActiveEditorIfNotDisplayed(AjaxRequestTarget aTarget)
    {
        if (isActiveEditorDisplayed()) {
            return;
        }

        setActiveEditor(aTarget, findFallbackEditor());
    }

    private DocumentEditor findFallbackEditor()
    {
        return getEditorPanels().stream() //
                .filter(this::isDisplayed) //
                .findFirst() //
                .orElse(null);
    }

    private boolean isActiveEditorDisplayed()
    {
        return getActiveEditor().map(this::isDisplayed).orElse(false);
    }

    private boolean isDisplayed(DocumentEditor aEditor)
    {
        if (!(aEditor instanceof Component component)) {
            return true;
        }

        return component.findParent(Page.class) != null && component.isVisibleInHierarchy();
    }

    @Override
    protected void onBeforeRender()
    {
        super.onBeforeRender();
        dropActiveEditorIfNotDisplayed(null);
    }

    @Override
    public Optional<DocumentEditor> findEditorFor(SourceDocument aDocument,
            AnnotationSet aDataOwner)
    {
        for (var panel : getEditorPanels()) {
            var state = panel.getAnnotatorState();
            if (Objects.equals(state.getDocument(), aDocument)
                    && Objects.equals(state.getDataOwner(), aDataOwner)) {
                return Optional.of(panel);
            }
        }

        return Optional.empty();
    }

    private Optional<DocumentEditor> findEmptyEditor()
    {
        for (var panel : getEditorPanels()) {
            if (panel.getAnnotatorState().getDocument() == null) {
                return Optional.of(panel);
            }
        }

        return Optional.empty();
    }

    @Override
    public List<DocumentEditor> findEditorsShowing(SourceDocument aDocument)
    {
        var editors = new ArrayList<DocumentEditor>();
        for (var panel : getEditorPanels()) {
            if (Objects.equals(panel.getAnnotatorState().getDocument(), aDocument)) {
                editors.add(panel);
            }
        }

        return editors;
    }

    @Override
    public void actionShowDocument(AjaxRequestTarget aTarget, DocumentEditor aPreferredEditor,
            SourceDocument aDocument, AnnotationSet aDataOwner, Range aRange)
        throws IOException, AnnotationException
    {
        actionShowDocument(aTarget, aPreferredEditor, aDocument, aDataOwner, aRange, null);
    }

    @Override
    public void actionShowDocument(AjaxRequestTarget aTarget, SourceDocument aDocument,
            AnnotationSet aDataOwner, Range aRange, List<VRange> aAdditionalPingRanges)
        throws IOException, AnnotationException
    {
        actionShowDocument(aTarget, null, aDocument, aDataOwner, aRange, aAdditionalPingRanges);
    }

    private void actionShowDocument(AjaxRequestTarget aTarget, DocumentEditor aPreferredEditor,
            SourceDocument aDocument, AnnotationSet aDataOwner, Range aRange,
            List<VRange> aAdditionalPingRanges)
        throws IOException, AnnotationException
    {
        Validate.notNull(aDocument, "Document must be specified");
        Validate.notNull(aDataOwner, "Data owner must be specified");

        var requesting = aPreferredEditor instanceof DocumentEditorPanel panel ? panel : null;
        var editor = resolveEditorFor(aTarget, aDocument, aDataOwner, requesting);

        // Opening the document notifies listeners and they must see which document is active
        setActiveEditor(aTarget, editor);

        editor.actionShowSelectedDocument(aTarget, aDocument, aDataOwner, aRange,
                aAdditionalPingRanges);

        fireEditorSetChanged(aTarget);
    }

    /**
     * @return the editor that should show the given document for the given data owner, already
     *         switched to that owner. Internal: callers outside go through
     *         {@link #actionShowDocument}, which also loads the document.
     */
    private DocumentEditor resolveEditorFor(AjaxRequestTarget aTarget, SourceDocument aDocument,
            AnnotationSet aDataOwner, DocumentEditorPanel aRequestingEditor)
        throws AnnotationException
    {
        Validate.notNull(aDataOwner, "Data owner must be specified");

        var awaiting = aRequestingEditor;

        // Document/data owner combination must not be opened twice
        var existing = findEditorFor(aDocument, aDataOwner);
        if (existing.isPresent()) {
            // Matched on the owner, so it already shows them - nothing to apply.
            return existing.get();
        }

        // Requested editor? Then fill that one
        if (awaiting != null && getEditorPanels().contains(awaiting)) {
            return awaiting;
        }

        // Nothing open yet - the first editor is the answer, there is nothing to disambiguate.
        if (getEditorPanels().isEmpty()) {
            openDocumentEditor(aTarget);
            return getEditorPanel(0);
        }

        // A pane that holds no document is free to take this one - splitting the view creates
        // exactly such a pane. Preferred over adding another: an empty pane is somewhere the user
        // has already made room, so filling it beats growing the layout further.
        var empty = findEmptyEditor();
        if (empty.isPresent()) {
            return empty.get();
        }

        // Did we reach the cap? If not add new editor
        if (canAddEditor()) {
            return addEditorPanel();
        }

        // ... otherwise replace the active editor's content
        if (activeEditor instanceof DocumentEditorPanel panel
                && getEditorPanels().contains(panel)) {
            return panel;
        }

        openDocumentEditor(aTarget);

        return getEditorPanel(0);
    }

    @Override
    protected void openEditorAt(AjaxRequestTarget aTarget, int aIndex, EditorRequest aRequest)
        throws IOException, AnnotationException
    {
        while (getEditorPanels().size() <= aIndex && canAddEditor()) {
            addEditorPanel();
        }

        var panel = getEditorPanel(aIndex);
        if (panel == null) {
            LOG.debug("Ignoring request for editor [{}]: the cap is [{}]", aIndex, getMaxEditors());
            return;
        }

        if (activeEditor == null) {
            setActiveEditor(aTarget, getEditorPanel(0));
        }

        panel.actionShowSelectedDocument(aTarget, aRequest.document(), aRequest.dataOwner(),
                Range.UNDEFINED, null);

        aRequest.focus().ifPresent(focus -> moveToUnit(aTarget, panel, focus));

        if (aTarget != null) {
            aTarget.add(editorPanelContainer);
        }

        fireEditorSetChanged(aTarget);
    }

    @Override
    protected void closeSurplusEditors(AjaxRequestTarget aTarget, int aRequestedCount)
    {
        for (var i = getEditorPanels().size() - 1; i >= aRequestedCount; i--) {
            var panel = getEditorPanel(i);
            if (panel == null || !canCloseEditor(panel)) {
                continue;
            }

            closeEditor(aTarget, panel);
        }
    }

    /**
     * @return whether the two panes can be offered scroll sync at all, i.e. whether there are
     *         exactly two panes.
     */
    public boolean isScrollSyncAvailable()
    {
        return getEditorPanels().size() == 2;
    }

    /**
     * @return whether the user wants the two panes to scroll together, and why they cannot right
     *         now.
     */
    public PausableSwitch<Obstacle> getScrollSync()
    {
        return scrollSync;
    }

    public void actionToggleScrollSync(AjaxRequestTarget aTarget)
    {
        scrollSync.toggle();

        refreshScrollSync(aTarget);
    }

    /**
     * @return the pane at the given index, but only while there are exactly two. With any other
     *         number of panes the link has no sides and emits nothing, which leaves the client-side
     *         hub to the reference document sidebar - it syncs with the pane while there is only
     *         one.
     */
    private DocumentEditorPanel getScrollSyncPane(int aIndex)
    {
        var panels = getEditorPanels();
        return panels.size() == 2 ? panels.get(aIndex) : null;
    }

    private void refreshScrollSync(AjaxRequestTarget aTarget)
    {
        if (aTarget == null) {
            return;
        }

        scrollSyncLink.linkScript(scrollSync.isEnabled()).ifPresent(aTarget::appendJavaScript);
    }

    @Override
    public void renderHead(IHeaderResponse aResponse)
    {
        super.renderHead(aResponse);

        scrollSyncLink.linkScript(scrollSync.isEnabled())
                .ifPresent(script -> aResponse.render(OnDomReadyHeaderItem.forScript(script)));
    }

    @OnEvent
    public void onAnnotatorViewportChanged(AnnotatorViewportChangedEvent aEvent)
    {
        if (!scrollSync.isEnabled()) {
            return;
        }

        scrollSyncLink.follow(aEvent);
    }

    @OnEvent
    public void onEditorContentReplaced(EditorContentReplacedEvent aEvent)
    {
        refreshScrollSync(aEvent.getRequestHandler());
    }

    @Override
    protected EditorUrlParameterStrategy getUrlParameterStrategy()
    {
        return urlParameterStrategy;
    }

    @Override
    protected List<DocumentEditorPanel> getEditorsForUrlFragment()
    {
        return getEditorPanels();
    }
}
