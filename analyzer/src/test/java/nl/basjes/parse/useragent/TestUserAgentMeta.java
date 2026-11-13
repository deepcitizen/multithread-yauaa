/*
 * Yet Another UserAgent Analyzer
 * Copyright (C) 2013-2026 Niels Basjes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nl.basjes.parse.useragent;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import nl.basjes.parse.useragent.AbstractUserAgentAnalyzer.CacheInstantiator;
import nl.basjes.parse.useragent.UserAgent.ImmutableUserAgent;
import nl.basjes.parse.useragent.UserAgent.MutableUserAgent;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static nl.basjes.parse.useragent.UserAgent.AGENT_NAME;
import static nl.basjes.parse.useragent.UserAgent.DEVICE_CLASS;
import static nl.basjes.parse.useragent.UserAgent.USERAGENT_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestUserAgentMeta {

    private static final String CHROME =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/114.0.0.0 Mobile Safari/537.36";

    private static final String FIREFOX =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    /**
     * Gives access to the cache that is used by the analyzer.
     */
    static final class CacheSpy implements CacheInstantiator {
        @Nullable private transient Map<String, ImmutableUserAgent> cache;

        @NonNull
        @Override
        public Map<String, ImmutableUserAgent> instantiateCache(int cacheSize) {
            Map<String, ImmutableUserAgent> newCache = new ConcurrentHashMap<>();
            cache = newCache;
            return newCache;
        }

        @NonNull
        Map<String, ImmutableUserAgent> getCache() {
            Map<String, ImmutableUserAgent> theCache = cache;
            assertNotNull(theCache, "The analyzer did not create a cache");
            return theCache;
        }

        @NonNull
        ImmutableUserAgent getCached(@NonNull String key) {
            ImmutableUserAgent cached = getCache().get(key);
            assertNotNull(cached, "Not in the cache: " + key);
            return cached;
        }
    }

    private static UserAgentAnalyzer.@NonNull UserAgentAnalyzerBuilder newBuilder(@NonNull CacheSpy cacheSpy) {
        return UserAgentAnalyzer
            .newBuilder()
            .hideMatcherLoadStats()
            .withField(DEVICE_CLASS)
            .withField(AGENT_NAME)
            .withCacheInstantiator(cacheSpy)
            .withCache(1000);
    }

    // ------------------------------------------

    @Test
    void theMetaOfAMutableUserAgent() {
        MutableUserAgent userAgent = new MutableUserAgent(CHROME);
        assertTrue(userAgent.getMeta().isEmpty());
        assertNull(userAgent.getMeta("a"));

        userAgent.setMeta("a", "1");
        userAgent.setMeta("b", "2");
        userAgent.setMeta("c", "3");
        userAgent.setMeta("a", "4"); // Replaces the value, the order of the keys stays the same
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(userAgent.getMeta().keySet()));
        assertEquals("4", userAgent.getMeta("a"));
        assertEquals("2", userAgent.getMeta("b"));

        userAgent.removeMeta("c");
        userAgent.removeMeta("does not exist");
        assertNull(userAgent.getMeta("c"));
        assertEquals(List.of("a", "b"), new ArrayList<>(userAgent.getMeta().keySet()));

        Map<String, String> meta = userAgent.getMeta();
        assertThrows(UnsupportedOperationException.class, () -> meta.put("x", "y"));
        assertThrows(NullPointerException.class, () -> userAgent.setMeta(null, "x"));
        assertThrows(NullPointerException.class, () -> userAgent.setMeta("x", null));

        // The meta is not a field
        assertFalse(userAgent.getAvailableFieldNamesSorted().contains("a"));
        assertFalse(userAgent.toMap().containsKey("a"));
        assertFalse(userAgent.toJson().contains("\"a\""));

        // The meta is not a result of the analysis so a reset does not change it
        userAgent.setUserAgentString(FIREFOX);
        assertEquals("4", userAgent.getMeta("a"));
    }

    @Test
    void theMetaIsCopied() {
        MutableUserAgent userAgent = new MutableUserAgent(CHROME);
        userAgent.setMeta("a", "1");
        userAgent.setMeta("b", "2");

        ImmutableUserAgent immutable = new ImmutableUserAgent(userAgent);
        assertEquals(userAgent.getMeta(), immutable.getMeta());
        assertEquals("1", immutable.getMeta("a"));
        Map<String, String> immutableMeta = immutable.getMeta();
        assertThrows(UnsupportedOperationException.class, () -> immutableMeta.put("x", "y"));

        // A copy: changing the original does not change the copy.
        userAgent.setMeta("a", "changed");
        assertEquals("1", immutable.getMeta("a"));

        MutableUserAgent mutable = new MutableUserAgent(immutable);
        assertEquals(immutable.getMeta(), mutable.getMeta());
        // A copy: changing the copy does not change the original.
        mutable.setMeta("c", "3");
        assertNull(immutable.getMeta("c"));
        mutable.removeMeta("c");

        // The meta is part of equals and hashCode
        ImmutableUserAgent same = new ImmutableUserAgent(mutable);
        assertEquals(immutable, same);
        assertEquals(immutable.hashCode(), same.hashCode());
        mutable.setMeta("a", "other");
        assertNotEquals(immutable, new ImmutableUserAgent(mutable));
        mutable.setMeta("a", "1");
        mutable.removeMeta("b");
        assertNotEquals(immutable, new ImmutableUserAgent(mutable));
    }

    @Test
    void metaOfAHandlerBeforeTheAnalysis() {
        CacheSpy cacheSpy = new CacheSpy();
        UserAgentAnalyzer uaa = newBuilder(cacheSpy)
            .addHandlerBeforeAnalysis(request -> {
                String userAgent = request.getUserAgentString();
                request.setMeta("checked", "yes");
                request.setMeta("source", "request");
                if (userAgent == null || !userAgent.startsWith("MyApp/")) {
                    return null;
                }
                MutableUserAgent result = request.newResult();
                result.set(DEVICE_CLASS, "Phone", 1000);
                result.setMeta("app",    "MyApp");
                result.setMeta("id",     userAgent.substring(userAgent.indexOf(' ') + 1));
                result.setMeta("source", "result"); // The result wins from the request
                return result;
            })
            .build();

        for (int i = 0; i < 100; i++) {
            String userAgent = "MyApp/1.2.3 " + i;
            ImmutableUserAgent result = uaa.parse(userAgent);
            assertEquals("Phone", result.getValue(DEVICE_CLASS));
            assertEquals(Map.of("checked", "yes", "source", "result", "app", "MyApp", "id", String.valueOf(i)),
                result.getMeta());
        }
        assertTrue(cacheSpy.getCache().isEmpty(), "The result of a handler before the analysis is never cached");

        // A request that is not handled gets the meta of the request.
        ImmutableUserAgent result = uaa.parse(CHROME);
        assertEquals(Map.of("checked", "yes", "source", "request"), result.getMeta());
        assertEquals("Phone", result.getValue(DEVICE_CLASS));
    }

    @Test
    void metaOfAHandlerAfterTheAnalysisIsCached() {
        AtomicInteger afterCalls = new AtomicInteger();
        CacheSpy cacheSpy = new CacheSpy();
        UserAgentAnalyzer uaa = newBuilder(cacheSpy)
            .addHandlerAfterAnalysis(request -> {
                request.setMeta("checkedBy",  "after");
                request.setMeta("analyzedAt", String.valueOf(afterCalls.incrementAndGet()));
                return null;
            })
            .addHandlerAfterAnalysis(request -> {
                ImmutableUserAgent analysisResult = request.getAnalysisResult();
                assertNotNull(analysisResult);
                assertTrue(analysisResult.getMeta().isEmpty(), "The analysis itself does not have any meta");
                assertEquals("after", request.getMeta("checkedBy"));
                if (!String.valueOf(request.getUserAgentString()).contains("Firefox")) {
                    return null;
                }
                MutableUserAgent result = new MutableUserAgent(analysisResult);
                result.setMeta("browser", "Firefox");
                return result;
            })
            .build();

        // Firefox: the meta of the request and of the returned result
        ImmutableUserAgent firefox = uaa.parse(FIREFOX);
        assertEquals(Map.of("checkedBy", "after", "analyzedAt", "1", "browser", "Firefox"), firefox.getMeta());
        assertSame(cacheSpy.getCached(FIREFOX), firefox, "All meta is cached");
        ImmutableUserAgent second = uaa.parse(FIREFOX);
        assertSame(firefox, second, "A cache hit");
        assertEquals("1", second.getMeta("analyzedAt"), "The meta comes from the cache");
        assertEquals(1, afterCalls.get());

        // Chrome: only the meta of the request
        ImmutableUserAgent chrome = uaa.parse(CHROME);
        assertEquals(Map.of("checkedBy", "after", "analyzedAt", "2"), chrome.getMeta());
        assertSame(cacheSpy.getCached(CHROME), chrome);
        assertSame(chrome, uaa.parse(CHROME));
        assertSame(chrome, uaa.parse(Collections.singletonMap(USERAGENT_HEADER, CHROME)));
        assertEquals(2, afterCalls.get());
    }

    @Test
    void handlersThatDoNotHandleTheRequestAddMeta() {
        AtomicInteger parses = new AtomicInteger();
        CacheSpy cacheSpy = new CacheSpy();
        UserAgentAnalyzer uaa = newBuilder(cacheSpy)
            .addHandlerBeforeAnalysis(request -> {
                assertTrue(request.getMeta().isEmpty());
                request.setMeta("b1",     "x");
                request.setMeta("shared", "before");
                request.setMeta("parse",  String.valueOf(parses.incrementAndGet()));
                return null;
            })
            .addHandlerBeforeAnalysis(request -> {
                // The meta of the handlers that were called before this one
                assertEquals("x", request.getMeta("b1"));
                request.setMeta("b2", request.getMeta("b1") + "y");
                return null;
            })
            .addHandlerAfterAnalysis(request -> {
                // The meta of the handlers before the analysis
                assertEquals("xy",     request.getMeta("b2"));
                assertEquals("before", request.getMeta("shared"));
                request.setMeta("a1",     "z");
                request.setMeta("shared", "after"); // The meta after the analysis wins
                return null;
            })
            .addHandlerAfterAnalysis(request -> {
                Map<String, String> meta = request.getMeta();
                assertEquals(Set.of("b1", "shared", "parse", "b2", "a1"), meta.keySet());
                assertEquals("after", meta.get("shared"));
                assertThrows(UnsupportedOperationException.class, () -> meta.put("x", "y"));
                return null;
            })
            .build();

        ImmutableUserAgent first = uaa.parse(CHROME);
        assertEquals(Map.of("b1", "x", "b2", "xy", "shared", "after", "parse", "1", "a1", "z"), first.getMeta());
        assertEquals(List.of("b1", "shared", "parse", "b2", "a1"), new ArrayList<>(first.getMeta().keySet()));

        // The meta of the request is added to every result (also to a cache hit) so it is never stored in the cache.
        assertEquals(Map.of("shared", "after", "a1", "z"), cacheSpy.getCached(CHROME).getMeta());

        ImmutableUserAgent second = uaa.parse(CHROME);
        assertEquals(Map.of("b1", "x", "b2", "xy", "shared", "after", "parse", "2", "a1", "z"), second.getMeta(),
            "A cache hit gets the meta of its own request (parse 2) and the cached meta after the analysis");
        assertNotSame(first, second);

        ImmutableUserAgent third = uaa.parse(Collections.singletonMap(USERAGENT_HEADER, CHROME));
        assertEquals(Map.of("b1", "x", "b2", "xy", "shared", "after", "parse", "3", "a1", "z"), third.getMeta());
        assertEquals(3, parses.get());
    }

    @Test
    void theMetaOfTheResultWinsFromTheMetaOfTheRequest() {
        UserAgentAnalyzerDirect uaa = UserAgentAnalyzerDirect
            .newBuilder()
            .hideMatcherLoadStats()
            .withField(DEVICE_CLASS)
            .addHandlerBeforeAnalysis(request -> {
                request.setMeta("before", "request");
                request.setMeta("both",   "before");
                if (!"Before".equals(request.getUserAgentString())) {
                    return null;
                }
                MutableUserAgent result = request.newResult();
                result.setMeta("before", "result");
                return result;
            })
            .addHandlerAfterAnalysis(request -> {
                request.setMeta("after", "request");
                if (!"After".equals(request.getUserAgentString())) {
                    return null;
                }
                MutableUserAgent result = new MutableUserAgent(request.getAnalysisResult());
                result.setMeta("after", "result");
                result.setMeta("both",  "after");
                return result;
            })
            .build();

        assertEquals(Map.of("before", "result",  "both", "before"),                    uaa.parse("Before").getMeta());
        assertEquals(Map.of("before", "request", "both", "after", "after", "result"),  uaa.parse("After").getMeta());
        assertEquals(Map.of("before", "request", "both", "before", "after", "request"), uaa.parse(CHROME).getMeta());
    }

    @Test
    void theMetaOfTheInput() {
        CacheSpy cacheSpy = new CacheSpy();
        UserAgentAnalyzer uaa = newBuilder(cacheSpy)
            .addHandlerBeforeAnalysis(request -> {
                request.setMeta("tenantBefore", String.valueOf(request.getMeta("tenant")));
                return null;
            })
            .addHandlerAfterAnalysis(request -> {
                assertNotNull(request.getMeta("tenant"), "A handler after the analysis also gets the meta of the input");
                request.setMeta("analyzed", "yes");
                return null;
            })
            .build();

        MutableUserAgent input = new MutableUserAgent(CHROME);
        input.setMeta("tenant", "A");
        ImmutableUserAgent first = uaa.parse(input);
        assertEquals(Map.of("tenant", "A", "tenantBefore", "A", "analyzed", "yes"), first.getMeta());
        assertEquals(Map.of("tenant", "A"), input.getMeta(), "The meta of the input is not changed");

        input = new MutableUserAgent(CHROME);
        input.setMeta("tenant", "B");
        ImmutableUserAgent second = uaa.parse(input);
        assertEquals(Map.of("tenant", "B", "tenantBefore", "B", "analyzed", "yes"), second.getMeta(),
            "A cache hit: the meta of the input and the handlers before the analysis are of this request");
        assertEquals(Map.of("analyzed", "yes"), cacheSpy.getCached(CHROME).getMeta());

        // Without the handlers
        UserAgentAnalyzerDirect direct = UserAgentAnalyzerDirect.newBuilder().hideMatcherLoadStats().withField(DEVICE_CLASS).build();
        input = new MutableUserAgent(FIREFOX);
        input.setMeta("tenant", "C");
        ImmutableUserAgent result = direct.parse(input);
        assertEquals("Desktop", result.getValue(DEVICE_CLASS));
        assertEquals(Map.of("tenant", "C"), result.getMeta());
    }

    @Test
    void theMetaWithoutACache() {
        AtomicInteger afterCalls = new AtomicInteger();
        UserAgentAnalyzerDirect uaa = UserAgentAnalyzerDirect
            .newBuilder()
            .hideMatcherLoadStats()
            .withField(DEVICE_CLASS)
            .addHandlerBeforeAnalysis(request -> {
                request.setMeta("before", "yes");
                return null;
            })
            .addHandlerAfterAnalysis(request -> {
                request.setMeta("after", "yes");
                request.setMeta("call",  String.valueOf(afterCalls.incrementAndGet()));
                return null;
            })
            .build();

        // Without a cache every parse is a new analysis.
        assertEquals(Map.of("before", "yes", "after", "yes", "call", "1"), uaa.parse(CHROME).getMeta());
        assertEquals(Map.of("before", "yes", "after", "yes", "call", "2"), uaa.parse(CHROME).getMeta());
        assertEquals(Map.of("before", "yes", "after", "yes", "call", "3"),
            uaa.parse(Collections.singletonMap(USERAGENT_HEADER, CHROME)).getMeta());
        assertEquals(Map.of("before", "yes", "after", "yes", "call", "4"), uaa.parse(new MutableUserAgent(CHROME)).getMeta());
    }

    @Test
    void theMetaOfTheNullUseragent() {
        AtomicInteger afterCalls = new AtomicInteger();
        CacheSpy cacheSpy = new CacheSpy();
        UserAgentAnalyzer uaa = newBuilder(cacheSpy)
            .addHandlerBeforeAnalysis(request -> {
                request.setMeta("before", "yes");
                return null;
            })
            .addHandlerAfterAnalysis(request -> {
                assertNull(request.getUserAgentString());
                request.setMeta("after", "yes");
                request.setMeta("call",  String.valueOf(afterCalls.incrementAndGet()));
                return null;
            })
            .build();

        // The result of the null useragent is cached (in its own field): also its meta.
        assertEquals(Map.of("before", "yes", "after", "yes", "call", "1"), uaa.parse((String) null).getMeta());
        assertEquals(Map.of("before", "yes", "after", "yes", "call", "1"), uaa.parse((String) null).getMeta());
        assertEquals(Map.of("before", "yes", "after", "yes", "call", "1"), uaa.parse((MutableUserAgent) null).getMeta());
        assertEquals(1, afterCalls.get());
    }

    @Test
    void theMetaWithClientHints() {
        AtomicInteger afterCalls = new AtomicInteger();
        CacheSpy cacheSpy = new CacheSpy();
        UserAgentAnalyzer uaa = newBuilder(cacheSpy)
            .addHandlerBeforeAnalysis(request -> {
                request.setMeta("platformHeader", String.valueOf(request.getHeaders().get("sec-ch-ua-platform")));
                return null;
            })
            .addHandlerAfterAnalysis(request -> {
                request.setMeta("call", String.valueOf(afterCalls.incrementAndGet()));
                return null;
            })
            .build();

        Map<String, String> headers = new HashMap<>();
        headers.put(USERAGENT_HEADER,     CHROME);
        headers.put("Sec-Ch-Ua-Platform", "\"Android\"");

        ImmutableUserAgent first  = uaa.parse(headers);
        ImmutableUserAgent second = uaa.parse(headers);
        assertEquals(Map.of("platformHeader", "\"Android\"", "call", "1"), first.getMeta());
        assertEquals(first, second, "The second parse is a cache hit");
        assertEquals(1, afterCalls.get());
        assertEquals(1, cacheSpy.getCache().size());
        for (ImmutableUserAgent cached : cacheSpy.getCache().values()) {
            assertEquals(Map.of("call", "1"), cached.getMeta());
        }
    }

    @Test
    void noMetaMeansNoCopies() {
        CacheSpy cacheSpy = new CacheSpy();
        UserAgentAnalyzer uaa = newBuilder(cacheSpy)
            .addHandlerBeforeAnalysis(request -> {
                if (FIREFOX.equals(request.getUserAgentString())) {
                    request.setMeta("same", "before");
                }
                return null;
            })
            .addHandlerAfterAnalysis(request -> {
                request.setMeta("same", "after");
                return null;
            })
            .build();

        // The handler before the analysis does not add meta: the cached instance itself is the result.
        ImmutableUserAgent chrome = uaa.parse(CHROME);
        assertSame(cacheSpy.getCached(CHROME), chrome);
        assertSame(chrome, uaa.parse(CHROME));

        // The meta of the request is already in the cached result (the cached value wins): no copy is needed.
        ImmutableUserAgent firefox = uaa.parse(FIREFOX);
        assertEquals(Map.of("same", "after"), firefox.getMeta());
        assertSame(cacheSpy.getCached(FIREFOX), firefox);
        assertSame(firefox, uaa.parse(FIREFOX));
    }

    @Test
    void manyThreads() throws Exception {
        CacheSpy cacheSpy = new CacheSpy();
        UserAgentAnalyzer uaa = newBuilder(cacheSpy)
            .addHandlerBeforeAnalysis(request -> {
                request.setMeta("thread", Thread.currentThread().getName());
                String userAgent = request.getUserAgentString();
                if (userAgent != null && userAgent.startsWith("MyApp/")) {
                    int start = userAgent.indexOf("did(") + 4;
                    request.setMeta("did", userAgent.substring(start, userAgent.indexOf(')', start)));
                }
                return null;
            })
            .addHandlerAfterAnalysis(request -> {
                request.setMeta("length", String.valueOf(String.valueOf(request.getUserAgentString()).length()));
                return null;
            })
            .build();

        List<String> userAgents = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            userAgents.add("MyApp/1.2.3 did(" + i + ") " + "x".repeat(i));
        }

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < 5000; i++) {
                        int index = ThreadLocalRandom.current().nextInt(userAgents.size());
                        String userAgent = userAgents.get(index);
                        ImmutableUserAgent result = uaa.parse(userAgent);
                        assertEquals(userAgent,                                result.getUserAgentString());
                        assertEquals(String.valueOf(index),                    result.getMeta("did"));
                        assertEquals(Thread.currentThread().getName(),         result.getMeta("thread"));
                        assertEquals(String.valueOf(userAgent.length()),       result.getMeta("length"));
                    }
                    return null;
                }));
            }
            for (Future<Object> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(userAgents.size(), cacheSpy.getCache().size());
        for (ImmutableUserAgent cached : cacheSpy.getCache().values()) {
            assertEquals(Set.of("length"), cached.getMeta().keySet(), "Only the meta after the analysis is cached");
        }
    }

    // ------------------------------------------

    @SuppressWarnings("unchecked")
    @NonNull
    private static <T extends Serializable> T javaRoundTrip(@NonNull T object) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(object);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    @NonNull
    private static MutableUserAgent kryoRoundTrip(@NonNull MutableUserAgent userAgent) {
        Kryo kryo = new Kryo();
        UserAgentAnalyzer.configureKryo(kryo);
        kryo.setRegistrationRequired(true);

        ByteBufferOutput output = new ByteBufferOutput(1_000_000, -1);
        kryo.writeClassAndObject(output, userAgent);
        ByteBuffer buffer = output.getByteBuffer();
        byte[] bytes = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(bytes);
        return (MutableUserAgent) kryo.readClassAndObject(new ByteBufferInput(bytes));
    }

    @Test
    void serialization() throws IOException, ClassNotFoundException {
        MutableUserAgent mutable = new MutableUserAgent(CHROME);
        mutable.set(DEVICE_CLASS, "Phone", 100);
        mutable.setMeta("a", "1");
        mutable.setMeta("b", "2");
        ImmutableUserAgent immutable = new ImmutableUserAgent(mutable);

        ImmutableUserAgent javaImmutable = javaRoundTrip(immutable);
        assertEquals(immutable, javaImmutable);
        assertEquals(List.of("a", "b"), new ArrayList<>(javaImmutable.getMeta().keySet()));

        for (MutableUserAgent copy : List.of(javaRoundTrip(mutable), kryoRoundTrip(mutable))) {
            assertEquals(mutable, copy);
            assertEquals(List.of("a", "b"), new ArrayList<>(copy.getMeta().keySet()));
        }

        // Without any meta
        MutableUserAgent noMeta = new MutableUserAgent(CHROME);
        assertTrue(kryoRoundTrip(noMeta).getMeta().isEmpty());
        assertTrue(javaRoundTrip(new ImmutableUserAgent(noMeta)).getMeta().isEmpty());
    }
}
