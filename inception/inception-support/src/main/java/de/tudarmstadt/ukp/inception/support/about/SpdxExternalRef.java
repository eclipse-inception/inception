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
package de.tudarmstadt.ukp.inception.support.about;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An SPDX external reference of a package, e.g. its Package URL (purl).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpdxExternalRef
    implements Serializable
{
    private static final long serialVersionUID = 4321766153409513582L;

    public static final String TYPE_PURL = "purl";

    private String referenceCategory;
    private String referenceType;
    private String referenceLocator;

    public String getReferenceCategory()
    {
        return referenceCategory;
    }

    public void setReferenceCategory(String aReferenceCategory)
    {
        referenceCategory = aReferenceCategory;
    }

    public String getReferenceType()
    {
        return referenceType;
    }

    public void setReferenceType(String aReferenceType)
    {
        referenceType = aReferenceType;
    }

    public String getReferenceLocator()
    {
        return referenceLocator;
    }

    public void setReferenceLocator(String aReferenceLocator)
    {
        referenceLocator = aReferenceLocator;
    }
}
