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
package de.tudarmstadt.ukp.clarin.webanno.ui.curation.component.model;

import java.io.Serializable;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument;
import de.tudarmstadt.ukp.clarin.webanno.security.model.User;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotatorState;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VDocument;

/**
 * A Model comprises of document and collection brat responses together with the username that will
 * populate the sentence with {@link AnnotationDocument}s
 */
public class AnnotatorSegmentState
    implements Serializable
{
    private static final long serialVersionUID = 1785666148278992450L;

    private User user;
    private AnnotatorState state;
    private VDocument vDocument;
    private int position;

    public AnnotatorSegmentState()
    {
        // Nothing to do
    }

    public void setVDocument(VDocument aVDocument)
    {
        vDocument = aVDocument;
    }

    public VDocument getVDocument()
    {
        return vDocument;
    }

    public User getUser()
    {
        return user;
    }

    public void setUser(User aUser)
    {
        user = aUser;
    }

    /**
     * @return the position of the annotator among all curatable annotators of the document. Used to
     *         number the annotator under anonymous curation, so the number does not depend on which
     *         annotators are currently shown.
     */
    public int getPosition()
    {
        return position;
    }

    public void setPosition(int aPosition)
    {
        position = aPosition;
    }

    public AnnotatorState getAnnotatorState()
    {
        return state;
    }

    public void setAnnotatorState(AnnotatorState aState)
    {
        state = aState;
    }
}
