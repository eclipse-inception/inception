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

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.builder.ToStringBuilder;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Document
{
    private final String text;
    private final Map<String, List<Annotation>> annotations;
    private final long version;

    public Document(@JsonProperty("text") String aText,
            @JsonProperty("annotations") Map<String, List<Annotation>> aAnnotations,
            @JsonProperty("version") long aVersion)
    {
        text = aText;
        annotations = aAnnotations;
        version = aVersion;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this) //
                .append("text", text) //
                .append("annotations", annotations) //
                .append("version", version) //
                .toString();
    }

    public String getText()
    {
        return text;
    }

    public Map<String, List<Annotation>> getAnnotations()
    {
        return annotations;
    }

    public long getVersion()
    {
        return version;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Document document = (Document) o;
        return getVersion() == document.getVersion() && getText().equals(document.getText())
                && getAnnotations().equals(document.getAnnotations());
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(getText(), getAnnotations(), getVersion());
    }
}
