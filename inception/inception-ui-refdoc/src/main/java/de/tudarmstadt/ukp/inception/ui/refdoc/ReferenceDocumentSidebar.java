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
package de.tudarmstadt.ukp.inception.ui.refdoc;

import static de.agilecoders.wicket.extensions.markup.html.bootstrap.icon.FontAwesome7IconType.link_s;
import static de.agilecoders.wicket.extensions.markup.html.bootstrap.icon.FontAwesome7IconType.link_slash_s;
import static de.tudarmstadt.ukp.inception.support.lambda.LambdaBehavior.enabledWhen;
import static de.tudarmstadt.ukp.inception.support.lambda.LambdaBehavior.visibleWhen;
import static de.tudarmstadt.ukp.inception.ui.refdoc.ReferenceDocumentSidebarState.KEY_REFERENCE_DOCUMENT_SIDEBAR_STATE;
import static java.util.Collections.emptyList;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.apache.wicket.AttributeModifier;
import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.OnDomReadyHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LambdaModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.wicketstuff.event.annotation.OnEvent;

import de.agilecoders.wicket.core.markup.html.bootstrap.image.Icon;
import de.tudarmstadt.ukp.inception.rendering.paging.NoPagingStrategy;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.preferences.UserPreferencesService;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationSet;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.security.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.actionbar.open.OpenDocumentDialog;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.editor.DocumentEditorPanel;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.editor.PausableSwitch;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.editor.ViewportSyncLink;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.editor.ViewportSyncLink.Obstacle;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.sidebar.AnnotationSidebar_ImplBase;
import de.tudarmstadt.ukp.inception.editor.state.AnnotatorStateImpl;
import de.tudarmstadt.ukp.inception.preferences.PreferencesService;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotatorState;
import de.tudarmstadt.ukp.inception.rendering.editorstate.DiamContext;
import de.tudarmstadt.ukp.inception.rendering.editorstate.DocumentEditor;
import de.tudarmstadt.ukp.inception.rendering.selection.AnnotatorViewportChangedEvent;
import de.tudarmstadt.ukp.inception.rendering.selection.EditorContentReplacedEvent;
import de.tudarmstadt.ukp.inception.rendering.selection.EditorSetChangedEvent;
import de.tudarmstadt.ukp.inception.support.lambda.LambdaAjaxLink;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.sidebar.SidebarContext;

public class ReferenceDocumentSidebar
    extends AnnotationSidebar_ImplBase
{
    private static final long serialVersionUID = 1L;

    private @SpringBean UserDao userRepository;
    private @SpringBean UserPreferencesService userPreferencesService;
    private @SpringBean PreferencesService preferencesService;

    private final IModel<AnnotatorState> stateModel;
    private final RefDocEditorPanel documentEditorPanel;
    private final OpenDocumentDialog openDialog;

    private final ViewportSyncLink scrollSyncLink;

    /** Whether the user wants the sidebar to scroll together with the document editor. */
    private final PausableSwitch<Obstacle> scrollSync;

    private WebMarkupContainer scrollSyncGroup;

    public ReferenceDocumentSidebar(String aId, SidebarContext aContext)
    {
        super(aId, aContext);

        var sessionOwner = userRepository.getCurrentUser();
        var project = aContext.getProject();

        var state = new AnnotatorStateImpl();
        stateModel = Model.of(state);
        state.setUser(sessionOwner);
        state.setPagingStrategy(new NoPagingStrategy());

        if (project != null) {
            state.setProject(project);
            // Populates visible/selectable layers and the annotation preferences (window size etc.)
            try {
                userPreferencesService.loadPreferences(state, sessionOwner.getUsername());
            }
            catch (IOException e) {
                throw new RuntimeException("Unable to load annotation preferences", e);
            }
        }

        state.setDocument(aContext.activeEditor() //
                .map(DiamContext::getAnnotatorState) //
                .map(AnnotatorState::getDocument) //
                .orElse(null), emptyList());

        documentEditorPanel = new RefDocEditorPanel("documentEditorPanel", stateModel);
        add(documentEditorPanel);

        scrollSyncLink = new ViewportSyncLink(() -> documentEditorPanel, this::getSyncPartner);
        scrollSync = new PausableSwitch<>(scrollSyncLink::getObstacle);
        if (project != null && loadSidebarState().isScrollSyncEnabled()) {
            // Restored even if the editors cannot be synchronized right now: it is then paused
            scrollSync.enable();
        }

        openDialog = new OpenDocumentDialog("openDialog",
                stateModel.map(AnnotatorState::getProject),
                stateModel.map(AnnotatorState::getDataOwner),
                getAnnotationPage()::listAccessibleDocuments, this::actionOpenDocument);
        add(openDialog);
    }

    private void actionOpenDocument(AjaxRequestTarget aTarget, SourceDocument aDocument,
            AnnotationSet aDataOwner, List<SourceDocument> aDocuments)
    {
        var state = stateModel.getObject();

        var user = userRepository.getUserOrCurationUser(aDataOwner.id());
        if (user != null) {
            state.setUser(user);
        }

        state.setDocument(aDocument, aDocuments);

        documentEditorPanel.actionLoadDocument(aTarget);
    }

    private ReferenceDocumentSidebarState loadSidebarState()
    {
        return preferencesService.loadTraitsForUserAndProject(KEY_REFERENCE_DOCUMENT_SIDEBAR_STATE,
                userRepository.getCurrentUser(), stateModel.getObject().getProject());
    }

    private void saveSidebarState(ReferenceDocumentSidebarState aState)
    {
        var project = stateModel.getObject().getProject();
        if (project == null) {
            return;
        }

        preferencesService.saveTraitsForUserAndProject(KEY_REFERENCE_DOCUMENT_SIDEBAR_STATE,
                userRepository.getCurrentUser(), project, aState);
    }

    private void actionShowOpenDocumentDialog(AjaxRequestTarget aTarget)
    {
        openDialog.show(aTarget, documentEditorPanel);
    }

    private void actionToggleScrollSync(AjaxRequestTarget aTarget)
    {
        scrollSync.toggle();

        var sidebarState = loadSidebarState();
        sidebarState.setScrollSyncEnabled(scrollSync.isEnabled());
        saveSidebarState(sidebarState);

        aTarget.add(scrollSyncGroup);
        scrollSyncScript().ifPresent(aTarget::appendJavaScript);
    }

    private Optional<String> scrollSyncScript()
    {
        return scrollSyncLink.linkScript(scrollSync.isEnabled());
    }

    /**
     * @return whether the sidebar offers scroll sync at all. Only while the workspace has exactly
     *         one editor pane: with two, it would be unclear to the user which pane the sidebar
     *         follows - even while the second pane is still empty. The one-pane rule also keeps the
     *         sidebar from competing with the split panes' own sync for a link in the client-side
     *         hub, which allows only one link per editor.
     */
    private boolean isScrollSyncEligible()
    {
        return getDocumentEditorManager().getEditors().size() == 1;
    }

    /**
     * @return the single editor pane of the workspace, whatever document and data owner it shows,
     *         or {@code null} if the sidebar is not {@link #isScrollSyncEligible() eligible}.
     */
    private DocumentEditor getSyncPartner()
    {
        var editors = getDocumentEditorManager().getEditors();
        return editors.size() == 1 ? editors.get(0) : null;
    }

    /**
     * What scroll sync does if nothing stands in its way. Otherwise why it cannot be switched on,
     * or, if it is on already, that it is paused for that reason and can still be switched off.
     */
    private String getScrollSyncTooltip()
    {
        var obstacle = scrollSync.getObstacle();
        if (obstacle.isEmpty()) {
            return "Synchronize scrolling with the document editor";
        }

        var reason = getScrollSyncObstacleDescription(obstacle.get());
        if (!scrollSync.isEnabled()) {
            return reason;
        }

        return "Paused: " + reason + ". Click to stop synchronizing.";
    }

    private String getScrollSyncObstacleDescription(Obstacle aObstacle)
    {
        return switch (aObstacle) {
        case NO_EDITOR, NO_DOCUMENT -> "Scroll synchronization is unavailable while the document "
                + "editor shows no document";
        case MIXED_PAGING -> "Scroll synchronization is unavailable while only one of the two "
                + "editors is paged";
        case DIFFERENT_PAGING -> "Scroll synchronization is unavailable while the two editors "
                + "page differently";
        case DIFFERENT_DOCUMENTS -> "Scroll synchronization is unavailable while a paged editor "
                + "shows a different document than the document editor";
        };
    }

    @Override
    public void renderHead(IHeaderResponse aResponse)
    {
        super.renderHead(aResponse);

        scrollSyncScript()
                .ifPresent(script -> aResponse.render(OnDomReadyHeaderItem.forScript(script)));
    }

    @OnEvent
    public void onAnnotatorViewportChanged(AnnotatorViewportChangedEvent aEvent)
    {
        if (!scrollSync.isEnabled() || !isScrollSyncEligible()) {
            return;
        }

        scrollSyncLink.follow(aEvent);
    }

    /**
     * Going from one pane to two must unlink the sidebar before the split panes can link to each
     * other, and going back to one must re-link it.
     */
    @OnEvent
    public void onEditorSetChanged(EditorSetChangedEvent aEvent)
    {
        refreshScrollSync(aEvent.getRequestHandler());
    }

    /**
     * Loading a document replaces the pane's editor component, and with it the id the editor is
     * registered under in the client-side hub. A link to the old id would silently stop syncing.
     */
    @OnEvent
    public void onEditorContentReplaced(EditorContentReplacedEvent aEvent)
    {
        if (aEvent.isFor(stateModel.getObject())) {
            // Our own document loads are handled in RefDocEditorPanel.onDocumentLoaded()
            return;
        }

        refreshScrollSync(aEvent.getRequestHandler());
    }

    private void refreshScrollSync(AjaxRequestTarget aTarget)
    {
        if (aTarget == null || scrollSyncGroup == null) {
            return;
        }

        aTarget.add(scrollSyncGroup);
        scrollSyncScript().ifPresent(aTarget::appendJavaScript);
    }

    /**
     * The reference document viewer. Read-only, lists the same documents the open dialog offers,
     * and contributes the sidebar-specific action bar items (open document, scroll sync toggle).
     */
    private class RefDocEditorPanel
        extends DocumentEditorPanel
        implements ReferenceDocumentEditor
    {
        private static final long serialVersionUID = -6631967232128940695L;

        public RefDocEditorPanel(String aId, IModel<AnnotatorState> aModel)
        {
            super(aId, ReferenceDocumentSidebar.this.getDocumentEditorManager(), aModel);
        }

        @Override
        protected boolean isActionBarCollapsed()
        {
            var project = stateModel.getObject().getProject();
            return project != null && loadSidebarState().isActionBarCollapsed();
        }

        @Override
        protected void onActionBarCollapsedChanged(boolean aCollapsed)
        {
            var sidebarState = loadSidebarState();
            sidebarState.setActionBarCollapsed(aCollapsed);
            saveSidebarState(sidebarState);
        }

        @Override
        protected Component createActionBarItemsBefore(String aId)
        {
            var fragment = new Fragment(aId, "openDocumentItem", ReferenceDocumentSidebar.this);

            fragment.add(new LambdaAjaxLink("showOpenDocumentDialog",
                    ReferenceDocumentSidebar.this::actionShowOpenDocumentDialog));

            return fragment;
        }

        @Override
        protected Component createActionBarItemsAfter(String aId)
        {
            var fragment = new Fragment(aId, "scrollSyncItem", ReferenceDocumentSidebar.this);

            // Two-way scroll synchronization with the document editor. Only offered while a
            // document is shown and the workspace has a single pane; whether it actually engages
            // is gated in ViewportSyncLink.
            // The tooltip sits on the group, not on the button: browsers deliver no pointer events
            // to a disabled button, so a title on the button itself would stay invisible precisely
            // when it explains why the toggle cannot be used.
            scrollSyncGroup = new WebMarkupContainer("scrollSyncGroup");
            scrollSyncGroup.setOutputMarkupPlaceholderTag(true);
            scrollSyncGroup.add(visibleWhen(
                    () -> getModelObject().getDocument() != null && isScrollSyncEligible()));
            scrollSyncGroup.add(AttributeModifier.replace("title",
                    LambdaModel.of(ReferenceDocumentSidebar.this::getScrollSyncTooltip)));
            fragment.add(scrollSyncGroup);

            var scrollSyncToggle = new LambdaAjaxLink("toggleScrollSync",
                    ReferenceDocumentSidebar.this::actionToggleScrollSync);
            scrollSyncToggle.add(new Icon("toggleScrollSyncIcon",
                    LambdaModel.of(() -> scrollSync.isEnabled() ? link_s : link_slash_s)));
            scrollSyncToggle.add(enabledWhen(() -> scrollSync.isToggleable()));
            scrollSyncGroup.add(scrollSyncToggle);

            return fragment;
        }

        @Override
        protected void onDocumentLoaded(AjaxRequestTarget aTarget)
        {
            aTarget.add(scrollSyncGroup);

            scrollSyncScript().ifPresent(aTarget::appendJavaScript);
        }
    }
}
