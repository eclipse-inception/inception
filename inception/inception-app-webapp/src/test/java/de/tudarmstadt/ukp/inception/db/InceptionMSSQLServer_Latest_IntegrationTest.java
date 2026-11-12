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
package de.tudarmstadt.ukp.inception.db;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import de.tudarmstadt.ukp.inception.support.test.docker.EnabledIfAmd64ImagesCanRun;

// SQL Server 2025 - latest release; mainstream support to 2031-01-06, EOL 2036-01-06
// Note: the 2025-latest tag floats, so the exact patch level shifts over time
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfAmd64ImagesCanRun
class InceptionMSSQLServer_Latest_IntegrationTest
{
    static final DockerImageName image = DockerImageName.parse("mcr.microsoft.com/mssql/server")
            .withTag("2025-latest");
    // .withTag("@sha256:45a1a9d13ca5574cf8e0fe4ae73ab77248b66d9c3132ac9658fb6c16dd72a8af");
    @SuppressWarnings("resource")
    static final MSSQLServerContainer<?> dbContainer = new MSSQLServerContainer<>(image) //
            .acceptLicense();

    static @TempDir Path tempDir;

    static ConfigurableApplicationContext appContext;

    @BeforeAll
    static void setupClass()
    {
        dbContainer.start();
    }

    @AfterAll
    static void tearDownClass()
    {
        if (appContext != null) {
            appContext.close();
        }

        if (dbContainer != null && dbContainer.isRunning()) {
            dbContainer.stop();
        }
    }

    @Nested
    class SpringApplcationContext
        extends InceptionIntegrationTest_ImplBase
    {
        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry)
        {
            registry.add("database.url", dbContainer::getJdbcUrl);
            registry.add("database.username", dbContainer::getUsername);
            registry.add("database.password", dbContainer::getPassword);
            registry.add("inception.home", () -> tempDir.toString());
        }

        @BeforeEach
        void setup(ConfigurableApplicationContext aAppContext)
        {
            appContext = aAppContext;
        }
    }
}
