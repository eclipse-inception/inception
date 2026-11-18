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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.invoke.MethodHandles;

import org.eclipse.rdf4j.http.client.spi.HttpRequest;
import org.eclipse.rdf4j.http.client.spi.HttpResponse;
import org.eclipse.rdf4j.http.client.spi.RDF4JHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries requests that a public SPARQL endpoint rejected because of rate limiting (HTTP 429) or
 * temporary unavailability (HTTP 503). The wait honours a {@code Retry-After} header given in
 * seconds and otherwise doubles with each attempt. This keeps tests against endpoints such as
 * AGROVOC from failing only because they were run too quickly.
 */
public class RateLimitRetryingHttpClient
    implements RDF4JHttpClient
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final int SC_TOO_MANY_REQUESTS = 429;
    private static final int SC_SERVICE_UNAVAILABLE = 503;

    static final long NO_RETRY = -1;

    private final RDF4JHttpClient delegate;
    private final int maxRetries;
    private final long initialIntervalMs;
    private final long maxIntervalMs;

    public RateLimitRetryingHttpClient(RDF4JHttpClient aDelegate, int aMaxRetries,
            long aInitialIntervalMs, long aMaxIntervalMs)
    {
        delegate = aDelegate;
        maxRetries = aMaxRetries;
        initialIntervalMs = aInitialIntervalMs;
        maxIntervalMs = aMaxIntervalMs;
    }

    @Override
    public HttpResponse execute(HttpRequest aRequest) throws IOException
    {
        var repeatable = aRequest.getBody().map(body -> body.isRepeatable()).orElse(true);

        for (var executionCount = 1;; executionCount++) {
            var response = delegate.execute(aRequest);

            if (!repeatable) {
                return response;
            }

            var interval = retryIntervalMs(response.getStatusCode(),
                    response.getHeader("Retry-After").orElse(null), executionCount);
            if (interval == NO_RETRY) {
                return response;
            }

            response.discardAndClose();

            try {
                Thread.sleep(interval);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting to retry");
            }
        }
    }

    /**
     * @return the time to wait before retrying, or {@link #NO_RETRY} if the response is to be
     *         returned as it is.
     */
    long retryIntervalMs(int aStatus, String aRetryAfter, int aExecutionCount)
    {
        if (aStatus != SC_TOO_MANY_REQUESTS && aStatus != SC_SERVICE_UNAVAILABLE) {
            return NO_RETRY;
        }

        if (aExecutionCount > maxRetries) {
            LOG.warn("Giving up after {} attempts (HTTP {})", aExecutionCount, aStatus);
            return NO_RETRY;
        }

        var interval = parseRetryAfterMs(aRetryAfter);
        if (interval < 0) {
            interval = initialIntervalMs << (aExecutionCount - 1);
        }
        interval = min(interval, maxIntervalMs);

        LOG.info("HTTP {} - retrying in {} ms (attempt {} of {})", aStatus, interval,
                aExecutionCount + 1, maxRetries + 1);
        return interval;
    }

    private static long parseRetryAfterMs(String aRetryAfter)
    {
        if (aRetryAfter == null) {
            return -1;
        }

        try {
            return Long.parseLong(aRetryAfter.trim()) * 1000;
        }
        catch (NumberFormatException e) {
            // An HTTP date is also allowed here - fall back to the exponential backoff then
            return -1;
        }
    }

    @Override
    public void close()
    {
        delegate.close();
    }
}
