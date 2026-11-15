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
package de.tudarmstadt.ukp.inception.kb.util;

import static java.lang.Math.min;

import java.lang.invoke.MethodHandles;

import org.apache.http.HttpResponse;
import org.apache.http.client.ServiceUnavailableRetryStrategy;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries requests that a public SPARQL endpoint rejected because of rate limiting (HTTP 429) or
 * temporary unavailability (HTTP 503). The wait honours a {@code Retry-After} header given in
 * seconds and otherwise doubles with each attempt. This keeps tests against endpoints such as
 * AGROVOC from failing only because they were run too quickly.
 */
public class RateLimitRetryStrategy
    implements ServiceUnavailableRetryStrategy
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final int SC_TOO_MANY_REQUESTS = 429;
    private static final int SC_SERVICE_UNAVAILABLE = 503;

    private final int maxRetries;
    private final long initialIntervalMs;
    private final long maxIntervalMs;
    private final ThreadLocal<Long> nextIntervalMs = new ThreadLocal<>();

    public RateLimitRetryStrategy(int aMaxRetries, long aInitialIntervalMs, long aMaxIntervalMs)
    {
        maxRetries = aMaxRetries;
        initialIntervalMs = aInitialIntervalMs;
        maxIntervalMs = aMaxIntervalMs;
    }

    @Override
    public boolean retryRequest(HttpResponse aResponse, int aExecutionCount, HttpContext aContext)
    {
        var status = aResponse.getStatusLine().getStatusCode();
        if (status != SC_TOO_MANY_REQUESTS && status != SC_SERVICE_UNAVAILABLE) {
            return false;
        }

        if (aExecutionCount > maxRetries) {
            LOG.warn("Giving up after {} attempts (HTTP {})", aExecutionCount, status);
            return false;
        }

        var interval = retryAfterMs(aResponse);
        if (interval < 0) {
            interval = initialIntervalMs << (aExecutionCount - 1);
        }
        interval = min(interval, maxIntervalMs);
        nextIntervalMs.set(interval);

        LOG.info("HTTP {} - retrying in {} ms (attempt {} of {})", status, interval,
                aExecutionCount + 1, maxRetries + 1);
        return true;
    }

    @Override
    public long getRetryInterval()
    {
        var interval = nextIntervalMs.get();
        return interval != null ? interval : initialIntervalMs;
    }

    private static long retryAfterMs(HttpResponse aResponse)
    {
        var header = aResponse.getFirstHeader("Retry-After");
        if (header == null) {
            return -1;
        }

        try {
            return Long.parseLong(header.getValue().trim()) * 1000;
        }
        catch (NumberFormatException e) {
            // An HTTP date is also allowed here - fall back to the exponential backoff then
            return -1;
        }
    }
}
