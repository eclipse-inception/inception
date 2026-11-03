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
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.builder.ToStringBuilder;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Annotation
{
    private final int begin;
    private final int end;
    private final Map<String, String> features;

    public Annotation(@JsonProperty("begin") int aBegin, @JsonProperty("end") int aEnd,
            @JsonProperty("features") Map<String, String> aFeatures)
    {
        begin = aBegin;
        end = aEnd;
        features = aFeatures != null ? aFeatures : Collections.emptyMap();
    }

    public Annotation(int aBegin, int aEnd)

    {
        this(aBegin, aEnd, Collections.emptyMap());
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this) //
                .append("begin", begin) //
                .append("end", end) //
                .append("features", features) //
                .toString();
    }

    public int getBegin()
    {
        return begin;
    }

    public int getEnd()
    {
        return end;
    }

    public Map<String, String> getFeatures()
    {
        return features;
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
        Annotation that = (Annotation) o;
        return getBegin() == that.getBegin() && getEnd() == that.getEnd()
                && getFeatures().equals(that.getFeatures());
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(getBegin(), getEnd(), getFeatures());
    }
}
