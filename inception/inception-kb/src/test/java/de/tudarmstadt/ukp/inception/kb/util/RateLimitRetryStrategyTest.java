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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class RateLimitRetryStrategyTest
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

        var status = execute(new RateLimitRetryStrategy(3, 10, 100));

        assertThat(status).isEqualTo(200);
        assertThat(requestCount).hasValue(3);
    }

    @Test
    void thatRetriesAreLimited() throws Exception
    {
        statuses.add(429);
        statuses.add(429);
        statuses.add(429);

        var status = execute(new RateLimitRetryStrategy(2, 10, 100));

        assertThat(status).isEqualTo(429);
        assertThat(requestCount).hasValue(3);
    }

    @Test
    void thatOtherErrorsAreNotRetried() throws Exception
    {
        statuses.add(400);

        var status = execute(new RateLimitRetryStrategy(3, 10, 100));

        assertThat(status).isEqualTo(400);
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void thatRetryAfterIsHonoured()
    {
        var sut = new RateLimitRetryStrategy(3, 1_000, 60_000);

        assertThat(sut.retryRequest(response(429, "7"), 1, null)).isTrue();
        assertThat(sut.getRetryInterval()).isEqualTo(7_000);
    }

    @Test
    void thatIntervalDoublesWithoutRetryAfter()
    {
        var sut = new RateLimitRetryStrategy(3, 1_000, 60_000);

        assertThat(sut.retryRequest(response(503, null), 1, null)).isTrue();
        assertThat(sut.getRetryInterval()).isEqualTo(1_000);
        assertThat(sut.retryRequest(response(503, null), 3, null)).isTrue();
        assertThat(sut.getRetryInterval()).isEqualTo(4_000);
    }

    @Test
    void thatIntervalIsCapped()
    {
        var sut = new RateLimitRetryStrategy(3, 1_000, 60_000);

        assertThat(sut.retryRequest(response(429, "3600"), 1, null)).isTrue();
        assertThat(sut.getRetryInterval()).isEqualTo(60_000);
    }

    @Test
    void thatRetryAfterDateFallsBackToBackoff()
    {
        var sut = new RateLimitRetryStrategy(3, 1_000, 60_000);

        assertThat(sut.retryRequest(response(429, "Wed, 21 Oct 2015 07:28:00 GMT"), 2, null))
                .isTrue();
        assertThat(sut.getRetryInterval()).isEqualTo(2_000);
    }

    private static HttpResponse response(int aStatus, String aRetryAfter)
    {
        var response = new BasicHttpResponse(HttpVersion.HTTP_1_1, aStatus, "test");
        if (aRetryAfter != null) {
            response.addHeader("Retry-After", aRetryAfter);
        }
        return response;
    }

    private int execute(RateLimitRetryStrategy aStrategy) throws IOException
    {
        try (var client = HttpClients.custom() //
                .setServiceUnavailableRetryStrategy(aStrategy) //
                .build()) {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/sparql";
            try (var response = client.execute(new HttpGet(url))) {
                EntityUtils.consume(response.getEntity());
                return response.getStatusLine().getStatusCode();
            }
        }
    }
}
