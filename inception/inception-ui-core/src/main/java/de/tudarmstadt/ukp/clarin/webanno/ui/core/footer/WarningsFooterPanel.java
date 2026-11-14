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
package de.tudarmstadt.ukp.clarin.webanno.ui.core.footer;

import static org.apache.commons.lang3.StringUtils.defaultString;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.wicket.Session;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.ResourceModel;
import org.apache.wicket.protocol.http.WebSession;
import org.apache.wicket.protocol.http.request.WebClientInfo;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.inception.support.db.DatabaseDriverService;
import de.tudarmstadt.ukp.inception.ui.core.config.WarningsProperties;

public class WarningsFooterPanel
    extends Panel
{
    private static final long serialVersionUID = 2586844743503672765L;

    private final static Logger LOG = LoggerFactory.getLogger(WarningsFooterPanel.class);

    private @SpringBean DatabaseDriverService dbDriverService;
    private @SpringBean WarningsProperties warningsProperties;

    private Label embeddedDbWarning;
    private Label browserWarning;
    private WebMarkupContainer warningsContainer;

    public WarningsFooterPanel(String aId)
    {
        super(aId);

        setOutputMarkupId(true);

        // set up warnings shown when using an embedded DB or some unsupported browser
        var isBrowserWarningVisible = isBrowserWarningVisible();
        var isDatabaseWarningVisible = isDatabaseWarningVisible();

        embeddedDbWarning = new Label("embeddedDbWarning", new ResourceModel("warning.database"));
        embeddedDbWarning.setVisible(isDatabaseWarningVisible);
        queue(embeddedDbWarning);

        browserWarning = new Label("browserWarning", new ResourceModel("warning.browser"));
        browserWarning.setVisible(isBrowserWarningVisible);
        queue(browserWarning);

        warningsContainer = new WebMarkupContainer("warnings");
        warningsContainer.setVisible(isBrowserWarningVisible || isDatabaseWarningVisible);
        queue(warningsContainer);
    }

    @Override
    public void renderHead(IHeaderResponse aResponse)
    {
        super.renderHead(aResponse);
    }

    private boolean isDatabaseWarningVisible()
    {
        boolean isUsingEmbeddedDatabase;
        try {
            var driver = dbDriverService.getDatabaseDriverName();
            isUsingEmbeddedDatabase = StringUtils.contains(driver.toLowerCase(Locale.US), "hsql");
        }
        catch (Throwable e) {
            LOG.warn("Unable to determine which database is being used", e);
            isUsingEmbeddedDatabase = false;
        }
        var showWarning = warningsProperties == null || warningsProperties.isEmbeddedDatabase();

        return isUsingEmbeddedDatabase && showWarning;
    }

    private boolean isBrowserWarningVisible()
    {
        var requestCycle = RequestCycle.get();
        WebClientInfo clientInfo;
        if (Session.exists()) {
            clientInfo = WebSession.get().getClientInfo();
        }
        else {
            clientInfo = new WebClientInfo(requestCycle);
        }

        var userAgent = defaultString(clientInfo.getUserAgent(), "").toLowerCase();
        var isUsingUnsupportedBrowser = !(userAgent.contains("safari")
                || userAgent.contains("chrome") || userAgent.contains("firefox"));

        var showWarning = warningsProperties == null || warningsProperties.isUnsupportedBrowser();

        return isUsingUnsupportedBrowser && showWarning;
    }
}
