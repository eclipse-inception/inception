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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.uima.cas.CAS;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.event.IEvent;
import org.apache.wicket.markup.html.WebPage;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.util.tester.WicketTester;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tudarmstadt.ukp.clarin.webanno.api.casstorage.CasProvider;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.inception.editor.AnnotationEditorBase;
import de.tudarmstadt.ukp.inception.editor.AnnotationEditorFactory;
import de.tudarmstadt.ukp.inception.editor.ContextMenuLookup;
import de.tudarmstadt.ukp.inception.editor.state.AnnotatorStateImpl;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotationActionHandler;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotatorState;
import de.tudarmstadt.ukp.inception.rendering.editorstate.DocumentEditorManager;
import de.tudarmstadt.ukp.inception.rendering.selection.EditorContentReplacedEvent;

class DocumentEditorPanelTest
{
    private WicketTester tester;
    private RecordingPage page;
    private FailingPanel sut;
    private SourceDocument document;

    @BeforeEach
    void setup()
    {
        tester = new WicketTester();

        var project = new Project("project");
        project.setId(1l);
        document = new SourceDocument("doc.txt", project, "text");
        document.setId(1l);

        var state = new AnnotatorStateImpl();
        state.setProject(project);

        // The page is never rendered, so its children are never initialized. That keeps the
        // action bar from asking for an annotation page, which is not what is tested here.
        sut = new FailingPanel("editor", mock(DocumentEditorManager.class), Model.of(state));
        page = new RecordingPage(sut);
    }

    @AfterEach
    void tearDown()
    {
        tester.destroy();
    }

    @Test
    void thatFailedLoadAnnouncesTheUnloadedEditor()
    {
        sut.getModelObject().setDocument(document, List.of(document));

        sut.actionLoadDocument(mock(AjaxRequestTarget.class), 0);

        assertThat(page.received) //
                .as("document and editor seen by the listeners") //
                .containsExactly(new Received(null, false));
    }

    @Test
    void thatFailedLoadWithoutRequestAnnouncesNothing()
    {
        sut.getModelObject().setDocument(document, List.of(document));

        sut.actionLoadDocument(null, 0);

        assertThat(sut.getModelObject().getDocument()).isNull();
        assertThat(page.received).isEmpty();
    }

    private record Received(SourceDocument document, boolean hasEditor) {}

    private static class RecordingPage
        extends WebPage
    {
        private static final long serialVersionUID = 1L;

        private final DocumentEditorPanel panel;
        private final List<Received> received = new ArrayList<>();

        RecordingPage(DocumentEditorPanel aPanel)
        {
            panel = aPanel;
            add(aPanel);
        }

        @Override
        public void onEvent(IEvent<?> aEvent)
        {
            super.onEvent(aEvent);

            if (aEvent.getPayload() instanceof EditorContentReplacedEvent) {
                received.add(new Received(panel.getModelObject().getDocument(),
                        panel.getEditor() != null));
            }
        }
    }

    private static class FailingPanel
        extends DocumentEditorPanel
    {
        private static final long serialVersionUID = 1L;

        FailingPanel(String aId, DocumentEditorManager aManager, IModel<AnnotatorState> aModel)
        {
            super(aId, aManager, aModel);
        }

        @Override
        protected AnnotationEditorFactory resolveEditorFactory(SourceDocument aDocument)
        {
            // A mock rather than an implementation: the factory interface drags in types this
            // module does not otherwise depend on
            var factory = mock(AnnotationEditorFactory.class);
            when(factory.create(any(), any(), any(), any(), any()))
                    .thenAnswer(call -> new StubEditor(call.getArgument(0), call.getArgument(1),
                            call.getArgument(2), call.getArgument(3), call.getArgument(4)));
            return factory;
        }

        @Override
        protected CAS readCas(SourceDocument aDocument) throws IOException
        {
            throw new IOException("CAS cannot be read");
        }
    }

    private static class StubEditor
        extends AnnotationEditorBase
    {
        private static final long serialVersionUID = 1L;

        StubEditor(String aId, IModel<AnnotatorState> aModel, DocumentEditorManager aManager,
                AnnotationActionHandler aActionHandler, CasProvider aCasProvider)
        {
            super(aId, aModel, aManager, aActionHandler, aCasProvider);
        }

        @Override
        protected void render(AjaxRequestTarget aTarget)
        {
            // Never rendered
        }

        @Override
        public Optional<ContextMenuLookup> getContextMenuLookup()
        {
            return Optional.empty();
        }
    }
}
