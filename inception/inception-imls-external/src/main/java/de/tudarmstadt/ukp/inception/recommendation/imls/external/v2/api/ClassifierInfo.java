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
package de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.api;

import java.io.Serializable;

import org.apache.commons.lang3.builder.ToStringBuilder;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ClassifierInfo
    implements Serializable
{
    private static final long serialVersionUID = 3361046124201929582L;

    private final String name;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public ClassifierInfo(@JsonProperty("name") String aName)
    {
        name = aName;
    }

    public String getName()
    {
        return name;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this).append("name", name).toString();
    }
}
