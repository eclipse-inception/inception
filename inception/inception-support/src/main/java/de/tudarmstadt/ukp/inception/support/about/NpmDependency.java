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

import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.normaliseLicense;
import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.normaliseSource;
import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.sourceFromPackageName;
import static java.util.Arrays.asList;
import static org.apache.commons.lang3.Strings.CS;

import java.io.Serializable;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NpmDependency
    implements Serializable
{
    private static final long serialVersionUID = 2906173536477925093L;
    private String name;
    private String licenses;
    private String repository;
    private String publisher;
    private String url;
    private String copyright;
    private String email;
    private String noticeText;

    public String getNoticeText()
    {
        return noticeText;
    }

    public void setNoticeText(String aNoticeText)
    {
        noticeText = aNoticeText;
    }

    public void setName(String aName)
    {
        name = aName;
    }

    public String getName()
    {
        return name;
    }

    public String getLicenses()
    {
        return licenses;
    }

    public void setLicenses(String aLicenses)
    {
        licenses = aLicenses;
    }

    public String getRepository()
    {
        return repository;
    }

    public void setRepository(String aRepository)
    {
        repository = aRepository;
    }

    public String getPublisher()
    {
        return publisher;
    }

    public void setPublisher(String aPublisher)
    {
        publisher = aPublisher;
    }

    public String getUrl()
    {
        return url;
    }

    public void setUrl(String aUrl)
    {
        url = aUrl;
    }

    public String getCopyright()
    {
        return copyright;
    }

    public void setCopyright(String aCopyright)
    {
        copyright = aCopyright;
    }

    public void setEmail(String aEmail)
    {
        email = aEmail;
    }

    public String getEmail()
    {
        return email;
    }

    public Dependency toDependency()
    {
        return new AbstractDependency()
        {
            @Override
            public String getUrl()
            {
                return NpmDependency.this.getUrl();
            }

            @Override
            public String getSource()
            {
                if (NpmDependency.this.getPublisher() == null) {
                    return sourceFromPackageName(getName());
                }

                return normaliseSource(NpmDependency.this.getPublisher());
            }

            @Override
            public String getName()
            {
                if (CS.contains(NpmDependency.this.getName(), "@")) {
                    return StringUtils.substringBeforeLast(NpmDependency.this.getName(), "@");
                }

                return NpmDependency.this.getName();
            }

            @Override
            public String getVersion()
            {
                if (CS.contains(NpmDependency.this.getName(), "@")) {
                    return StringUtils.substringAfterLast(NpmDependency.this.getName(), "@");
                }

                return null;
            }

            @Override
            public List<String> getLicenses()
            {
                return asList(normaliseLicense(NpmDependency.this.getLicenses()));
            }
        };
    }
}
