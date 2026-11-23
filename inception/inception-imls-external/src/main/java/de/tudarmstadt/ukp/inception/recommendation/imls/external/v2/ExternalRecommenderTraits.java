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

import java.io.Serializable;

import de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.api.ClassifierInfo;

public class ExternalRecommenderTraits
    implements Serializable
{
    private static final long serialVersionUID = -3109239605741337123L;

    private String remoteUrl = "http://localhost:8000";
    private ClassifierInfo classifierInfo;
    private boolean trainable;

    public String getRemoteUrl()
    {
        return remoteUrl;
    }

    public void setRemoteUrl(String aRemoteUrl)
    {
        remoteUrl = aRemoteUrl;
    }

    public ClassifierInfo getClassifierInfo()
    {
        return classifierInfo;
    }

    public void setClassifierInfo(ClassifierInfo aClassifierInfo)
    {
        classifierInfo = aClassifierInfo;
    }

    public boolean isTrainable()
    {
        return trainable;
    }

    public void setTrainable(boolean aTrainable)
    {
        trainable = aTrainable;
    }
}
