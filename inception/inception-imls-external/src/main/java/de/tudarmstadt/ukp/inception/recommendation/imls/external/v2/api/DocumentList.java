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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.builder.ToStringBuilder;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DocumentList
{
    private final List<String> names;
    private final List<Long> versions;

    public DocumentList(@JsonProperty("names") List<String> aNames,
            @JsonProperty("versions") List<Long> aVersions)
    {
        names = Collections.unmodifiableList(aNames);
        versions = Collections.unmodifiableList(aVersions);
    }

    public List<String> getNames()
    {
        return names;
    }

    public List<Long> getVersions()
    {
        return versions;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        DocumentList that = (DocumentList) o;
        return Objects.equals(getNames(), that.getNames())
                && Objects.equals(getVersions(), that.getVersions());
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(getNames(), getVersions());
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this) //
                .append("names", names) //
                .append("versions", versions) //
                .toString();
    }
}
