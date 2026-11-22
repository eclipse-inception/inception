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
package de.tudarmstadt.ukp.clarin.webanno.support.standalone;

import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.LOGO;
import static java.awt.BorderLayout.CENTER;
import static java.awt.BorderLayout.SOUTH;
import static java.awt.Color.WHITE;
import static javax.swing.BorderFactory.createEmptyBorder;
import static org.slf4j.LoggerFactory.getLogger;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.util.Optional;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

import org.slf4j.Logger;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.context.event.ApplicationContextInitializedEvent;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.ContextRefreshedEvent;

import de.tudarmstadt.ukp.inception.support.about.ApplicationInformation;
import de.tudarmstadt.ukp.inception.support.spring.StartupProgressInfoEvent;

public class LoadingSplashScreen
{
    private static final Logger LOG = getLogger(MethodHandles.lookup().lookupClass());

    private static Optional<SplashWindow> setupScreen(URL aSplashScreenImageUrl, URL aIconUrl,
            String aApplicationName)
    {
        if (GraphicsEnvironment.isHeadless()) {
            return Optional.empty();
        }

        try {
            var window = new SplashWindow(aSplashScreenImageUrl, aIconUrl, aApplicationName);
            window.setVisible(true);

            return Optional.of(window);
        }
        catch (UnsatisfiedLinkError e) {
            return Optional.empty();
        }
    }

    public static Optional<SplashWindow> setupScreen(String aApplicationName)
    {
        // Swing also picks up the @2x variant next to it for high-resolution screens
        var splashScreenImageUrl = ApplicationInformation.class.getResource(LOGO);
        var iconUrl = LoadingSplashScreen.class.getResource("/icon.png");

        if (splashScreenImageUrl == null || iconUrl == null) {
            LOG.error("Unable to locate splash screen and icon resources");
            return Optional.empty();
        }

        return setupScreen(splashScreenImageUrl, iconUrl, aApplicationName);
    }

    public static class SplashWindow
        extends JFrame
    {
        private static final long serialVersionUID = 2429606748142131008L;

        private JLabel info;
        private boolean disposed = false;
        private String applicationName;
        private TrayIcon trayIcon;

        public SplashWindow(URL aSplashScreenImageUrl, URL aIconUrl, String aApplicationName)
        {
            applicationName = aApplicationName;

            var l = new JLabel(new ImageIcon(aSplashScreenImageUrl));
            getContentPane().add(l, CENTER);

            info = new JLabel(applicationName + " is loading...", SwingConstants.CENTER);
            info.setBackground(WHITE);
            info.setOpaque(true);
            info.setBorder(createEmptyBorder(5, 5, 5, 5));
            getContentPane().add(info, SOUTH);

            var img = new ImageIcon(aIconUrl);
            setIconImage(img.getImage());

            StandaloneUserInterface.setupDockIcon();

            setUndecorated(true);
            pack();

            var screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            var labelSize = l.getPreferredSize();
            setLocation(screenSize.width / 2 - (labelSize.width / 2),
                    screenSize.height / 2 - (labelSize.height / 2));

            addMouseMotionListener(new MouseMotionListener()
            {
                private int mx, my;

                @Override
                public void mouseMoved(MouseEvent aEvent)
                {
                    mx = aEvent.getXOnScreen();
                    my = aEvent.getYOnScreen();
                }

                @Override
                public void mouseDragged(MouseEvent aEvent)
                {
                    var p = SplashWindow.this.getLocation();
                    p.x += aEvent.getXOnScreen() - mx;
                    p.y += aEvent.getYOnScreen() - my;
                    mx = aEvent.getXOnScreen();
                    my = aEvent.getYOnScreen();
                    SplashWindow.this.setLocation(p);
                }
            });

            setVisible(true);

            // Tray
            if (SystemTray.isSupported()) {
                try {
                    trayIcon = StandaloneUserInterface.makeStartupSystemTrayMenu(aApplicationName);
                }
                catch (AWTException e) {
                    LOG.error("Could not add startup menu to tray", e);
                }
            }
        }

        @Override
        public void dispose()
        {
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
            }

            disposed = true;
            super.dispose();
        }

        public boolean isDisposed()
        {
            return disposed;
        }

        public void setInfo(String aInfo)
        {
            if (info == null) {
                return;
            }

            info.setText(aInfo);
        }

        public void handleEvent(ApplicationEvent aEvent)
        {
            if (aEvent instanceof ApplicationReadyEvent
                    || aEvent instanceof ShutdownDialogAvailableEvent) {
                dispose();
                return;
            }

            if (aEvent instanceof AvailabilityChangeEvent) {
                // We can ignore this one...
                return;
            }

            if (!isDisposed()) {
                setInfo(applicationName + " is loading... - " + mapEvent(aEvent));
            }

            if (aEvent instanceof ApplicationFailedEvent) {
                new StartupErrorHandler(applicationName)
                        .handleError((ApplicationFailedEvent) aEvent);
            }
        }

        public String mapEvent(ApplicationEvent aEvent)
        {
            if (aEvent instanceof ApplicationStartingEvent) {
                return "Application starting";
            }

            if (aEvent instanceof ApplicationEnvironmentPreparedEvent) {
                return "Application environment prepared";
            }

            if (aEvent instanceof ApplicationContextInitializedEvent) {
                return "Application context initialized";
            }

            if (aEvent instanceof ApplicationPreparedEvent) {
                return "Application prepared";
            }

            if (aEvent instanceof ServletWebServerInitializedEvent) {
                return "Servlet web server initialized";
            }

            if (aEvent instanceof ContextRefreshedEvent) {
                return "Context refreshed";
            }

            if (aEvent instanceof ApplicationStartedEvent) {
                return "Application started";
            }

            if (aEvent instanceof StartupProgressInfoEvent) {
                return ((StartupProgressInfoEvent) aEvent).getMessage();
            }

            LOG.debug("Unmapped event: " + aEvent);
            return aEvent.getClass().getSimpleName();
        }
    }
}
