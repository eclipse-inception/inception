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

import static de.tudarmstadt.ukp.inception.kb.util.RateLimitRetryingHttpClient.NO_RETRY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.rdf4j.http.client.spi.HttpRequest;
import org.eclipse.rdf4j.http.client.spi.RDF4JHttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class RateLimitRetryingHttpClientTest
{
    private HttpServer server;
    private Queue<Integer> statuses;
    private AtomicInteger requestCount;

    @BeforeEach
    void setUp() throws IOException
    {
        statuses = new ConcurrentLinkedQueue<>();
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sparql", exchange -> {
            requestCount.incrementAndGet();
            var status = statuses.isEmpty() ? 200 : statuses.poll();
            if (status != 200) {
                exchange.getResponseHeaders().add("Retry-After", "0");
            }
            var body = ("status " + status).getBytes(UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown()
    {
        server.stop(0);
    }

    @Test
    void thatRateLimitedRequestsAreRetried() throws Exception
    {
        statuses.add(429);
        statuses.add(503);

        var status = execute(3, 10, 100);

        assertThat(status).isEqualTo(200);
        assertThat(requestCount).hasValue(3);
    }

    @Test
    void thatRetriesAreLimited() throws Exception
    {
        statuses.add(429);
        statuses.add(429);
        statuses.add(429);

        var status = execute(2, 10, 100);

        assertThat(status).isEqualTo(429);
        assertThat(requestCount).hasValue(3);
    }

    @Test
    void thatOtherErrorsAreNotRetried() throws Exception
    {
        statuses.add(400);

        var status = execute(3, 10, 100);

        assertThat(status).isEqualTo(400);
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void thatRetryAfterIsHonoured()
    {
        var sut = new RateLimitRetryingHttpClient(null, 3, 1_000, 60_000);

        assertThat(sut.retryIntervalMs(429, "7", 1)).isEqualTo(7_000);
    }

    @Test
    void thatIntervalDoublesWithoutRetryAfter()
    {
        var sut = new RateLimitRetryingHttpClient(null, 3, 1_000, 60_000);

        assertThat(sut.retryIntervalMs(503, null, 1)).isEqualTo(1_000);
        assertThat(sut.retryIntervalMs(503, null, 3)).isEqualTo(4_000);
    }

    @Test
    void thatIntervalIsCapped()
    {
        var sut = new RateLimitRetryingHttpClient(null, 3, 1_000, 60_000);

        assertThat(sut.retryIntervalMs(429, "3600", 1)).isEqualTo(60_000);
    }

    @Test
    void thatRetryAfterDateFallsBackToBackoff()
    {
        var sut = new RateLimitRetryingHttpClient(null, 3, 1_000, 60_000);

        assertThat(sut.retryIntervalMs(429, "Wed, 21 Oct 2015 07:28:00 GMT", 2)).isEqualTo(2_000);
    }

    @Test
    void thatExhaustedRetriesAreNotRetried()
    {
        var sut = new RateLimitRetryingHttpClient(null, 3, 1_000, 60_000);

        assertThat(sut.retryIntervalMs(429, "1", 4)).isEqualTo(NO_RETRY);
    }

    private int execute(int aMaxRetries, long aInitialIntervalMs, long aMaxIntervalMs)
        throws IOException
    {
        try (var client = new RateLimitRetryingHttpClient(RDF4JHttpClients.newDefaultClient(),
                aMaxRetries, aInitialIntervalMs, aMaxIntervalMs)) {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/sparql");
            try (var response = client.execute(HttpRequest.newBuilder("GET", uri).build())) {
                response.discard();
                return response.getStatusCode();
            }
        }
    }
}
