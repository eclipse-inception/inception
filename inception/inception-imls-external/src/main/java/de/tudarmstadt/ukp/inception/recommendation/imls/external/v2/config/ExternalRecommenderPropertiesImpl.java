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
package de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.config;

import static java.time.temporal.ChronoUnit.SECONDS;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <p>
 * This class is exposed as a Spring Component via {@link ExternalRecommenderAutoConfiguration}.
 * </p>
 */
@ConfigurationProperties("recommender.external")
public class ExternalRecommenderPropertiesImpl
    implements ExternalRecommenderProperties
{
    private Duration connectTimeout = Duration.of(30, SECONDS);
    private Duration readTimeout = Duration.of(30, SECONDS);

    @Override
    public Duration getConnectTimeout()
    {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration aConnectTimeout)
    {
        connectTimeout = aConnectTimeout;
    }

    @Override
    public Duration getReadTimeout()
    {
        return readTimeout;
    }

    public void setReadTimeout(Duration aReadTimeout)
    {
        readTimeout = aReadTimeout;
    }

}
