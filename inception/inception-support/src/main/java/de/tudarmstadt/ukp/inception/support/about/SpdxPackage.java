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
import static java.util.Arrays.asList;

import java.io.Serializable;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SpdxPackage
    implements Serializable
{
    private static final long serialVersionUID = -146919416362600033L;

    /** The type prefix of an SPDX entity such as {@code Organization: Example Inc.} */
    private static final Pattern ENTITY_TYPE = Pattern.compile("^(Person|Organization|Tool):\\s*");

    private String name;
    private String supplier;
    private String originator;
    private String versionInfo;
    private String description;
    private String homepage;
    private String downloadLocation;
    private String packageFileName;
    private String licenseDeclared;
    private String licenseConcluded;
    private String licenseComments;
    private List<String> hasFiles;
    private List<SpdxExternalRef> externalRefs;

    /**
     * @return the name of an SPDX entity such as {@code Organization: Example Inc.}, without its
     *         type.
     */
    public static String entityName(String aEntity)
    {
        return aEntity == null ? null : ENTITY_TYPE.matcher(aEntity).replaceFirst("");
    }

    public String getDescription()
    {
        return description;
    }

    public void setDescription(String aDescription)
    {
        description = aDescription;
    }

    public void setHomepage(String aHomepage)
    {
        homepage = aHomepage;
    }

    public String getHomepage()
    {
        return homepage;
    }

    public String getSupplier()
    {
        return supplier;
    }

    public void setSupplier(String aSupplier)
    {
        supplier = aSupplier;
    }

    public void setOriginator(String aOriginator)
    {
        originator = aOriginator;
    }

    public String getOriginator()
    {
        return originator;
    }

    public String getVersionInfo()
    {
        return versionInfo;
    }

    public void setVersionInfo(String aVersionInfo)
    {
        versionInfo = aVersionInfo;
    }

    public String getDownloadLocation()
    {
        return downloadLocation;
    }

    public void setDownloadLocation(String aDownloadLocation)
    {
        downloadLocation = aDownloadLocation;
    }

    public String getPackageFileName()
    {
        return packageFileName;
    }

    public void setPackageFileName(String aPackageFileName)
    {
        packageFileName = aPackageFileName;
    }

    public String getLicenseDeclared()
    {
        return licenseDeclared;
    }

    public void setLicenseDeclared(String aLicenseDeclared)
    {
        licenseDeclared = aLicenseDeclared;
    }

    public String getLicenseConcluded()
    {
        return licenseConcluded;
    }

    public void setLicenseConcluded(String aLicenseConcluded)
    {
        licenseConcluded = aLicenseConcluded;
    }

    public String getLicenseComments()
    {
        return licenseComments;
    }

    public void setLicenseComments(String aLicenseComments)
    {
        licenseComments = aLicenseComments;
    }

    public List<String> getHasFiles()
    {
        return hasFiles;
    }

    public void setHasFiles(List<String> aHasFiles)
    {
        hasFiles = aHasFiles;
    }

    public List<SpdxExternalRef> getExternalRefs()
    {
        return externalRefs;
    }

    public void setExternalRefs(List<SpdxExternalRef> aExternalRefs)
    {
        externalRefs = aExternalRefs;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String aName)
    {
        name = aName;
    }

    public Dependency toDependency()
    {
        return new AbstractDependency()
        {
            @Override
            public String getUrl()
            {
                if (SpdxPackage.this.getHomepage() != null) {
                    return SpdxPackage.this.getHomepage();
                }

                return SpdxPackage.this.getDownloadLocation();
            }

            @Override
            public String getSource()
            {
                if (SpdxPackage.this.getOriginator() != null) {
                    return normaliseSource(entityName(SpdxPackage.this.getOriginator()));
                }

                return normaliseSource(entityName(SpdxPackage.this.getSupplier()));
            }

            @Override
            public String getName()
            {
                return SpdxPackage.this.getName();
            }

            @Override
            public String getVersion()
            {
                return SpdxPackage.this.getVersionInfo();
            }

            @Override
            public List<String> getLicenses()
            {
                if (SpdxPackage.this.getLicenseConcluded() != null) {
                    return asList(normaliseLicense(SpdxPackage.this.getLicenseConcluded()));
                }
                return asList(normaliseLicense(SpdxPackage.this.getLicenseDeclared()));
            }
        };
    }

}
