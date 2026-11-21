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
package de.tudarmstadt.ukp.inception;

import static de.tudarmstadt.ukp.inception.INCEpTION.INCEPTION_BASE_PACKAGE;
import static de.tudarmstadt.ukp.inception.INCEpTION.WEBANNO_BASE_PACKAGE;
import static de.tudarmstadt.ukp.inception.support.SettingsUtil.getApplicationHome;
import static de.tudarmstadt.ukp.inception.support.SettingsUtil.setGlobalLogFolder;
import static org.apache.uima.cas.impl.CASImpl.ALWAYS_HOLD_ONTO_FSS;
import static org.springframework.boot.WebApplicationType.NONE;
import static org.springframework.boot.WebApplicationType.SERVLET;

import java.io.IOException;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.LogManager;

import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.Banner;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import de.tudarmstadt.ukp.clarin.webanno.support.standalone.LoadingSplashScreen;
import de.tudarmstadt.ukp.clarin.webanno.support.standalone.LoadingSplashScreen.SplashWindow;
import de.tudarmstadt.ukp.inception.app.config.InceptionApplicationContextInitializer;
import de.tudarmstadt.ukp.inception.app.config.InceptionBanner;
import de.tudarmstadt.ukp.inception.support.db.EmbeddedDatabaseBackupHandler;
import de.tudarmstadt.ukp.inception.support.deployment.DeploymentModeService;

/**
 * Boots INCEpTION in standalone JAR or WAR modes.
 */
// @formatter:off
@SpringBootApplication(
        scanBasePackages = { INCEPTION_BASE_PACKAGE, WEBANNO_BASE_PACKAGE })
@AutoConfigurationPackage(basePackages = { INCEPTION_BASE_PACKAGE, WEBANNO_BASE_PACKAGE })
@EntityScan(basePackages = { INCEPTION_BASE_PACKAGE, WEBANNO_BASE_PACKAGE })
@EnableAsync
@EnableMethodSecurity(prePostEnabled = true)
//@formatter:on
public class INCEpTION
    extends SpringBootServletInitializer
{
    static final String INCEPTION_BASE_PACKAGE = "de.tudarmstadt.ukp.inception";
    static final String WEBANNO_BASE_PACKAGE = "de.tudarmstadt.ukp.clarin.webanno";

    /**
     * Called when deployed in an external servlet container.
     */
    @Override
    protected SpringApplicationBuilder createSpringApplicationBuilder()
    {
        var builder = super.createSpringApplicationBuilder();
        builder.properties("running.from.commandline=false");
        builder.profiles(DeploymentModeService.PROFILE_EXTERNAL_SERVER);
        builder.profiles(DeploymentModeService.PROFILE_APPLICATION_MODE);

        init(builder);

        return builder;
    }

    /**
     * Common initialization.
     */
    private static void init(SpringApplicationBuilder aBuilder)
    {
        if (Boolean.getBoolean("inception.dev")) {
            System.setProperty("wicket.core.settings.debug.enabled", "true");
            System.setProperty("wicket.core.settings.general.configuration-type", "development");
            System.setProperty("webanno.debug.enforce_cas_thread_lock", "true");
            aBuilder.profiles(DeploymentModeService.PROFILE_DEVELOPMENT_MODE);
        }
        else {
            aBuilder.profiles(DeploymentModeService.PROFILE_PRODUCTION_MODE);
        }

        // Route JUL through log4j
        if (Boolean.getBoolean("inception.jul-logging")) {
            java.util.logging.LogManager.getLogManager().reset();
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();
            LogManager.getLogManager().getLogger("").setLevel(Level.ALL);
        }

        // We rely on FS IDs being stable, so we need to enable this
        System.setProperty(ALWAYS_HOLD_ONTO_FSS, "true");

        // We do not want DKPro Core to try and auto-download anything
        System.setProperty("dkpro.model.repository.offline", "true");

        try {
            new EmbeddedDatabaseBackupHandler().maybeBackupEmbeddedDatabase();
        }
        catch (IOException e) {
            throw new RuntimeException("Error trying to perform a backup of the embedded database",
                    e);
        }

        aBuilder.banner(new InceptionBanner());
        aBuilder.initializers(new InceptionApplicationContextInitializer());
        aBuilder.headless(false);

        // Traditionally, the INCEpTION configuration file is called settings.properties and is
        // either located in inception.home or under the user's home directory. Make sure we pick
        // it up from there in addition to reading the built-in application.yml file.
        aBuilder.properties("spring.config.additional-location="
                + "optional:${inception.home:${user.home}/.inception}/settings.properties;"
                + "optional:${inception.home:${user.home}/.inception}/settings.yml");
    }

    /**
     * Called when deployed using the internal server.
     */
    protected static void run(String[] args, Class<?>... aSources)
    {
        var context = start(args, INCEpTION.class);
        if (isCliCommandMode(args)) {
            context.close();
        }
    }

    /**
     * Called when deployed using the internal server with custom properties.
     */
    protected static ConfigurableApplicationContext start(String[] args, Properties aProperties,
            Class<?>... aSources)
    {
        var builder = new SpringApplicationBuilder();

        // Add the main application as the root Spring context
        builder.sources(aSources);

        // Signal that we may need the shutdown dialog
        builder.properties("running.from.commandline=true");

        // Add custom properties
        if (aProperties != null) {
            builder.properties(aProperties);
        }

        builder.profiles(DeploymentModeService.PROFILE_INTERNAL_SERVER);

        Optional<SplashWindow> splash;

        // If invoking in command-mode via the command line, do not start a web server
        if (isCliCommandMode(args)) {
            builder.web(NONE);
            builder.bannerMode(Banner.Mode.OFF);
            builder.headless(true);
            splash = Optional.empty();
            builder.profiles(DeploymentModeService.PROFILE_CLI_MODE);
        }
        else {
            builder.web(SERVLET);
            splash = LoadingSplashScreen.setupScreen("Eclipse INCEpTION");
            builder.headless(splash.isEmpty());
            builder.profiles(DeploymentModeService.PROFILE_APPLICATION_MODE);

            // Activated by the MSI / macOS app bundle launchers via -Dinception.app-bundle=true
            // in jpackage --java-options. Applies desktop-bundle defaults from
            // application-app-bundle.yml (e.g. binding the web server to localhost only).
            if (Boolean.getBoolean(DeploymentModeService.SYSTEM_PROPERTY_APP_BUNDLE)) {
                builder.profiles(DeploymentModeService.PROFILE_APP_BUNDLE);
            }
        }

        init(builder);

        setGlobalLogFolder(getApplicationHome().toPath().resolve("log"));

        builder.listeners(event -> splash.ifPresent(_splash -> _splash.handleEvent(event)));
        var context = builder.run(args);

        return context;
    }

    /**
     * Called when deployed using the internal server.
     */
    protected static ConfigurableApplicationContext start(String[] args, Class<?>... aSources)
    {
        return start(args, null, aSources);
    }

    private static boolean isCliCommandMode(String[] args)
    {
        return args.length > 0;
    }

    public static void main(String[] args) throws Exception
    {
        run(args, INCEpTION.class);
    }
}
