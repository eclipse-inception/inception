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
package de.tudarmstadt.ukp.inception.ui.core.about;

import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.LOGO;
import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.LOGO_2X;
import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.loadJsonDependencies;
import static java.lang.String.CASE_INSENSITIVE_ORDER;
import static java.lang.String.join;
import static java.util.Comparator.comparing;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.wicket.RuntimeConfigurationType.DEVELOPMENT;

import java.time.Year;

import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.image.Image;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.model.StringResourceModel;
import org.apache.wicket.request.resource.PackageResourceReference;
import org.wicketstuff.annotation.mount.MountPath;

import de.tudarmstadt.ukp.clarin.webanno.ui.core.page.ApplicationPageBase;
import de.tudarmstadt.ukp.inception.support.about.ApplicationInformation;
import de.tudarmstadt.ukp.inception.support.about.Dependency;

@MountPath("/about")
public class AboutPage
    extends ApplicationPageBase
{
    private static final long serialVersionUID = -3156459881790077891L;

    public AboutPage()
    {
        setStatelessHint(true);
        setVersioned(false);

        var buf = new StringBuilder();

        buf.append("===== Licenses =====\n");

        var dependencies = loadJsonDependencies();

        // dependencies = dependencies.stream() //
        // .filter(d -> !d.getName().startsWith("@inception-project/")) //
        // .filter(d -> !d.getName().startsWith("INCEpTION")) //
        // .collect(toSet());

        dependencies.stream() //
                .flatMap(d -> d.getLicenses().stream()) //
                .sorted(comparing(identity(), CASE_INSENSITIVE_ORDER)).distinct() //
                .forEach(l -> buf.append(l).append("\n"));
        buf.append("\n");

        buf.append("===== Sources =====\n");

        dependencies.stream() //
                .map(d -> defaultString(d.getSource(), "UNKNOWN (no source declared)")) //
                .sorted(comparing(identity(), CASE_INSENSITIVE_ORDER)).distinct() //
                .forEach(l -> buf.append(l).append("\n"));
        buf.append("\n");

        var developerMode = isDeveloper();
        var groupedBySource = dependencies.stream().collect(
                groupingBy(d -> defaultString(d.getSource(), "UNKNOWN (no source declared)")));
        for (var groupKey : groupedBySource.keySet().stream().sorted().toList()) {
            buf.append("===== ").append(groupKey).append(" =====\n");
            for (var dep : groupedBySource.get(groupKey).stream()
                    .sorted(comparing(Dependency::getName)).distinct().toList()) {
                buf.append(dep.getName());
                if (dep.getVersion() != null && developerMode) {
                    buf.append(" ").append(dep.getVersion());
                }
                if (dep.getUrl() != null) {
                    buf.append(" (").append(dep.getUrl()).append(")");
                }
                if (dep.getLicenses() != null && !dep.getLicenses().isEmpty()) {
                    buf.append(" licensed as ");
                    buf.append(join(", ", dep.getLicenses()));
                }
                buf.append("\n");
            }
            buf.append("\n");
        }

        var logo = new Image("logo",
                new PackageResourceReference(ApplicationInformation.class, LOGO),
                new PackageResourceReference(ApplicationInformation.class, LOGO_2X));
        logo.setXValues("1x", "2x");
        add(logo);

        add(new Label("dependencies", buf));
        var copyright = new Label("copyright", new StringResourceModel("copyright")
                .setParameters(Integer.toString(Year.now().getValue())));
        copyright.setEscapeModelStrings(false); // SAFE - I18N STRING WITH NO USER-CONTROLLABLE DATA
        add(copyright);
        add(new BookmarkablePageLink<>("home", getApplication().getHomePage()));
    }

    private boolean isDeveloper()
    {
        return DEVELOPMENT.equals(getApplication().getConfigurationType());
    }
}
