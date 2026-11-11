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
package de.tudarmstadt.ukp.inception.recommendation.imls.external.v2;

import static de.tudarmstadt.ukp.inception.support.lambda.LambdaBehavior.visibleWhen;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

import java.net.URI;
import java.util.Collections;

import org.apache.wicket.AttributeModifier;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.feedback.IFeedback;
import org.apache.wicket.markup.html.form.CheckBox;
import org.apache.wicket.markup.html.form.DropDownChoice;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.LambdaChoiceRenderer;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.model.CompoundPropertyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.apache.wicket.validation.validator.UrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.inception.recommendation.api.model.Recommender;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.DefaultTrainableRecommenderTraitsEditor;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommendationEngineFactory;
import de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.api.ClassifierInfo;
import de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.api.ExternalRecommenderApiException;
import de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.api.ExternalRecommenderV2Api;
import de.tudarmstadt.ukp.inception.support.lambda.LambdaAjaxButton;
import de.tudarmstadt.ukp.inception.support.lambda.LambdaAjaxFormComponentUpdatingBehavior;

public class ExternalRecommenderTraitsEditor
    extends DefaultTrainableRecommenderTraitsEditor
{
    private static final Logger LOG = LoggerFactory.getLogger(ExternalRecommenderV2Api.class);

    private static final long serialVersionUID = 1677442652521110324L;

    private static final String MID_FORM = "form";

    private @SpringBean RecommendationEngineFactory<ExternalRecommenderTraits> toolFactory;

    private final ExternalRecommenderTraits traits;
    private final LambdaAjaxButton<?> checkServerConnectionButton;
    private final LambdaAjaxButton<?> checkClassifierButton;
    private final DropDownChoice<ClassifierInfo> classifierInfoSelect;

    public ExternalRecommenderTraitsEditor(String aId, IModel<Recommender> aRecommender)
    {
        super(aId, aRecommender);

        traits = toolFactory.readTraits(aRecommender.getObject());

        Form<ExternalRecommenderTraits> form = new Form<>(MID_FORM,
                CompoundPropertyModel.of(Model.of(traits)))
        {
            private static final long serialVersionUID = -3109239605742291123L;

            @Override
            protected void onSubmit()
            {
                super.onSubmit();
                toolFactory.writeTraits(aRecommender.getObject(), traits);
            }
        };

        TextField<String> remoteUrl = new TextField<>("remoteUrl");
        remoteUrl.setRequired(true);
        remoteUrl.add(new UrlValidator());
        form.add(remoteUrl);

        checkServerConnectionButton = new LambdaAjaxButton<>("checkServerConnection",
                this::checkServerConnection);
        form.add(checkServerConnectionButton);

        // TODO: Make combo box
        classifierInfoSelect = new DropDownChoice<>("classifierInfo");
        classifierInfoSelect.setChoiceRenderer(new LambdaChoiceRenderer<>(ClassifierInfo::getName));
        classifierInfoSelect.setOutputMarkupId(true);
        form.add(classifierInfoSelect);

        checkClassifierButton = new LambdaAjaxButton<>("checkClassifier", this::checkClassifier);
        form.add(checkClassifierButton);

        CheckBox trainable = new CheckBox("trainable");
        trainable.setOutputMarkupId(true);
        trainable.add(new LambdaAjaxFormComponentUpdatingBehavior("change",
                _target -> _target.add(getTrainingStatesChoice())));
        form.add(trainable);

        getTrainingStatesChoice().add(visibleWhen(() -> trainable.getModelObject() == true));

        add(form);
    }

    private void checkServerConnection(AjaxRequestTarget aTarget, Form<?> aForm)
        throws ExternalRecommenderApiException
    {
        URI uri = URI.create(traits.getRemoteUrl());
        ExternalRecommenderV2Api api = new ExternalRecommenderV2Api(uri);

        String newClass;
        if (api.isReachable()) {
            newClass = "btn btn-success";
            classifierInfoSelect.setChoices(api.getAvailableClassifiers());
        }
        else {
            newClass = "btn btn-danger";
            classifierInfoSelect.setChoices(Collections.emptyList());
        }

        checkServerConnectionButton.add(AttributeModifier.append("class", newClass));

        aTarget.add(checkServerConnectionButton);
        aTarget.add(classifierInfoSelect);
    }

    private void checkClassifier(AjaxRequestTarget aTarget, Form<?> aForm)
    {
        URI uri = URI.create(traits.getRemoteUrl());
        ExternalRecommenderV2Api api = new ExternalRecommenderV2Api(uri);

        String newClass;
        String classifierName = traits.getClassifierInfo().getName();

        try {
            api.getClassifierInfo(classifierName);
            if (isNotEmpty(classifierName)) {
                newClass = "btn btn-success";
            }
            else {
                newClass = "btn btn-danger";
            }

            checkClassifierButton.add(AttributeModifier.append("class", newClass));
            aTarget.add(checkClassifierButton);
        }
        catch (ExternalRecommenderApiException e) {
            error(e.getMessage());
            aTarget.addChildren(getPage(), IFeedback.class);
        }
    }

}
