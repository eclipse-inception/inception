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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

// PostgreSQL 18 - latest major (EOL 2030-11)
@Testcontainers(disabledWithoutDocker = true)
class InceptionPostgresql_Latest_IntegrationTest
{
    // static DockerImageName image = DockerImageName.parse("pgvector/pgvector:pg16")
    // .asCompatibleSubstituteFor("postgres");
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> dbContainer = new PostgreSQLContainer<>(
            "postgres:18.6-alpine") //
                    .withDatabaseName("testdb") //
                    .withUsername("test") //
                    .withPassword("test");

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
