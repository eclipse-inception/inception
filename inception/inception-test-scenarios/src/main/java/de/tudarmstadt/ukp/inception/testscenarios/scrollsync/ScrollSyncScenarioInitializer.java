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
package de.tudarmstadt.ukp.inception.testscenarios.scrollsync;

import static de.tudarmstadt.ukp.clarin.webanno.model.PermissionLevel.ANNOTATOR;
import static de.tudarmstadt.ukp.clarin.webanno.model.PermissionLevel.CURATOR;
import static de.tudarmstadt.ukp.clarin.webanno.model.PermissionLevel.MANAGER;
import static de.tudarmstadt.ukp.inception.project.initializers.basic.BasicSpanLayerInitializer.BASIC_SPAN_LABEL_FEATURE_NAME;
import static de.tudarmstadt.ukp.inception.project.initializers.basic.BasicSpanLayerInitializer.BASIC_SPAN_LAYER_NAME;
import static de.tudarmstadt.ukp.inception.support.uima.AnnotationBuilder.buildAnnotation;
import static de.tudarmstadt.ukp.inception.testscenarios.ScenarioDocuments.importDocument;
import static de.tudarmstadt.ukp.inception.testscenarios.accounts.ScenarioAccount.ANNOTATOR_1;
import static java.util.Arrays.asList;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.core.annotation.Order;

import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.project.initializers.QuickProjectInitializer;
import de.tudarmstadt.ukp.clarin.webanno.security.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.security.model.User;
import de.tudarmstadt.ukp.inception.documents.api.DocumentService;
import de.tudarmstadt.ukp.inception.project.api.ProjectInitializationRequest;
import de.tudarmstadt.ukp.inception.project.api.ProjectInitializer;
import de.tudarmstadt.ukp.inception.project.api.ProjectService;
import de.tudarmstadt.ukp.inception.project.initializers.basic.BasicSpanLayerInitializer;
import de.tudarmstadt.ukp.inception.testscenarios.accounts.ScenarioAccountService;
import de.tudarmstadt.ukp.inception.testscenarios.config.InceptionTestScenariosAutoConfiguration;

/**
 * Builds a project for scroll synchronization between two editors - the reference document sidebar
 * and the document editor, or the two panes of the split view.
 * <ul>
 * <li><code>scrollsync-source.html</code> and <code>scrollsync-translation.html</code> have the
 * <b>same heading structure</b> (eight chapters with the same heading ids), but the chapters differ
 * greatly in length: chapter 1 of the translation is far longer than in the source. An alignment by
 * section therefore lands somewhere else than an alignment by character offset or scroll fraction,
 * which makes the two distinguishable. HTML opens in an unpaged editor.</li>
 * <li><code>scrollsync-gap.html</code> is the source <b>without chapter 3</b>. Scrolling the source
 * through chapter 3 must hold the gap document at the start of chapter 4, not send it back into
 * chapter 2.</li>
 * <li><code>scrollsync-other.html</code> has a <b>different</b> heading structure.</li>
 * <li><code>scrollsync-paged.txt</code> is long plain text, which opens in a paged editor. It has
 * been annotated by <code>scenario-annotator-1</code>, so it can be opened for two data owners side
 * by side.</li>
 * </ul>
 * <p>
 * <b>This class is exposed as a Spring Component via
 * {@link InceptionTestScenariosAutoConfiguration#scrollSyncScenarioInitializer}.</b>
 */
@Order(9000)
public class ScrollSyncScenarioInitializer
    implements QuickProjectInitializer
{
    // HtmlFormatSupport.ID - a literal so that the module does not depend on inception-io-html
    // just for a constant the compiler would inline anyway
    private static final String FORMAT_HTML = "htmldoc";

    private static final String DOC_SOURCE = "scrollsync-source.html";
    private static final String DOC_TRANSLATION = "scrollsync-translation.html";
    private static final String DOC_GAP = "scrollsync-gap.html";
    private static final String DOC_OTHER = "scrollsync-other.html";
    private static final String DOC_PAGED = "scrollsync-paged.txt";

    private static final int CHAPTERS = 8;

    private final DocumentService documentService;
    private final ProjectService projectService;
    private final UserDao userService;
    private final ScenarioAccountService scenarioAccountService;

    public ScrollSyncScenarioInitializer(DocumentService aDocumentService,
            ProjectService aProjectService, UserDao aUserService,
            ScenarioAccountService aScenarioAccountService)
    {
        documentService = aDocumentService;
        projectService = aProjectService;
        userService = aUserService;
        scenarioAccountService = aScenarioAccountService;
    }

    @Override
    public String getName()
    {
        return "TEST: scroll sync";
    }

    @Override
    public Optional<String> getDescription()
    {
        return Optional.of("Two HTML documents with the same structure, one missing a section, "
                + "one with a different structure, and a long paged text annotated by another "
                + "annotator. For scroll sync between the reference document and the editor, or "
                + "between split panes.");
    }

    @Override
    public boolean hasExamples()
    {
        return true;
    }

    @Override
    public boolean alreadyApplied(Project aProject)
    {
        return false;
    }

    @Override
    public List<Class<? extends ProjectInitializer>> getDependencies()
    {
        return asList(BasicSpanLayerInitializer.class);
    }

    @Override
    public void configure(ProjectInitializationRequest aRequest) throws IOException
    {
        var project = aRequest.getProject();
        project.setName(userService.getCurrentUsername() + " - TEST scroll sync");
        project.setDescription("""
                Scroll sync test scenario.

                `scrollsync-source.html` and `scrollsync-translation.html` share their heading
                structure (chapters 1-8), but chapter 1 of the translation is much longer.
                `scrollsync-gap.html` is the source without chapter 3.
                `scrollsync-other.html` has a different structure. `scrollsync-paged.txt` is long
                plain text in a paged editor, annotated by `scenario-annotator-1` (password
                `admin`).
                """);

        var currentUser = userService.getCurrentUser();
        projectService.assignRole(project, currentUser, MANAGER, CURATOR, ANNOTATOR);

        importDocument(documentService, project, getClass(), DOC_SOURCE, FORMAT_HTML);
        importDocument(documentService, project, getClass(), DOC_TRANSLATION, FORMAT_HTML);
        importDocument(documentService, project, getClass(), DOC_GAP, FORMAT_HTML);
        importDocument(documentService, project, getClass(), DOC_OTHER, FORMAT_HTML);
        var paged = importDocument(documentService, project, getClass(), DOC_PAGED);

        var otherAnnotator = scenarioAccountService.join(project, ANNOTATOR_1);
        annotate(paged, otherAnnotator);
    }

    private void annotate(SourceDocument aDocument, User aUser) throws IOException
    {
        var annotationDocument = documentService.createOrGetAnnotationDocument(aDocument, aUser);
        var cas = documentService.readAnnotationCas(annotationDocument);

        var builder = buildAnnotation(cas, BASIC_SPAN_LAYER_NAME);
        builder.withFeature(BASIC_SPAN_LABEL_FEATURE_NAME, "OTHER");
        builder.on("Line 0001").buildAndAddToIndexes();

        documentService.writeAnnotationCas(cas, aDocument, aUser);
    }
}
