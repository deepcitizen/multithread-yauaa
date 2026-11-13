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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static nl.basjes.parse.useragent.UserAgent.AGENT_NAME;
import static nl.basjes.parse.useragent.UserAgent.AGENT_NAME_VERSION;
import static nl.basjes.parse.useragent.UserAgent.AGENT_NAME_VERSION_MAJOR;
import static nl.basjes.parse.useragent.UserAgent.AGENT_VERSION;
import static nl.basjes.parse.useragent.UserAgent.AGENT_VERSION_MAJOR;
import static nl.basjes.parse.useragent.UserAgent.DEVICE_CLASS;
import static nl.basjes.parse.useragent.UserAgent.OPERATING_SYSTEM_NAME;
import static nl.basjes.parse.useragent.UserAgent.USERAGENT_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestUserAgentHandlers {

    private static final String CHROME =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/114.0.0.0 Mobile Safari/537.36";

    private static final String FIREFOX =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    /**
     * Like an application that puts unique ids in its useragent: "MyApp/1.2.3 did(uuid) aid(random)".
     * @return A new (random) useragent of this application.
     */
    @NonNull
    private static String myAppUserAgent() {
        StringBuilder aid = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            aid.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        }
        return "MyApp/1.2.3 did(" + UUID.randomUUID() + ") aid(" + aid + ")";
    }

    /**
     * Handles the useragents of "MyApp" (without looking at the random parts).
     */
    static final class MyAppHandler implements UserAgentHandler {
        static final String PREFIX = "MyApp/";

        @Nullable
        @Override
        public MutableUserAgent handle(@NonNull Request request) {
            String userAgent = request.getUserAgentString();
            if (userAgent == null || !userAgent.startsWith(PREFIX)) {
                return null;
            }
            int end = userAgent.indexOf(' ');
            String version = userAgent.substring(PREFIX.length(), end < 0 ? userAgent.length() : end);

            MutableUserAgent result = request.newResult();
            result.set(DEVICE_CLASS,          "Phone",   1000);
            result.set(OPERATING_SYSTEM_NAME, "Android", 1000);
            result.set(AGENT_NAME,            "MyApp",   1000);
            result.set(AGENT_VERSION,         version,   1000);
            return result;
        }
    }

    /**
     * Replaces the result of the built-in analysis for the useragents of "Acme".
     */
    static final class AcmeHandler implements UserAgentHandler {
        @Nullable
        @Override
        public MutableUserAgent handle(@NonNull Request request) {
            ImmutableUserAgent analysisResult = request.getAnalysisResult();
            assertNotNull(analysisResult, "A handler after the analysis must get the result of the analysis");
            String userAgent = request.getUserAgentString();
            if (userAgent == null || !userAgent.startsWith("Acme")) {
                return null;
            }
            MutableUserAgent result = new MutableUserAgent(analysisResult);
            result.setForced(DEVICE_CLASS, "Robot",    1000);
            result.setForced(AGENT_NAME,   "AcmeBot",  1000);
            return result;
        }
    }

    /**
     * Counts how often the wrapped handler is called and how often it returns a result.
     */
    static final class CountingHandler implements UserAgentHandler {
        @NonNull private final UserAgentHandler handler;
        @NonNull private final AtomicInteger    calls   = new AtomicInteger();
        @NonNull private final AtomicInteger    results = new AtomicInteger();

        CountingHandler(@NonNull UserAgentHandler handler) {
            this.handler = handler;
        }

        @Nullable
        @Override
        public MutableUserAgent handle(@NonNull Request request) {
            calls.incrementAndGet();
            MutableUserAgent result = handler.handle(request);
            if (result != null) {
                results.incrementAndGet();
            }
            return result;
        }
    }

    /**
     * Gives access to the cache that is used by the analyzer.
     */
    static final class RecordingCacheInstantiator implements CacheInstantiator {
        @Nullable private transient Map<String, ImmutableUserAgent> cache;

        @NonNull
        @Override
        public Map<String, ImmutableUserAgent> instantiateCache(int cacheSize) {
            cache = new ConcurrentHashMap<>();
            return cache;
        }

        int size() {
            Map<String, ImmutableUserAgent> theCache = cache;
            return theCache == null ? 0 : theCache.size();
        }
    }

    @NonNull private static final CountingHandler            BEFORE  = new CountingHandler(new MyAppHandler());
    @NonNull private static final CountingHandler            AFTER   = new CountingHandler(new AcmeHandler());
    @NonNull private static final RecordingCacheInstantiator CACHE   = new RecordingCacheInstantiator();

    // Created once in createAnalyzers()
    private static UserAgentAnalyzer       analyzer;        // Caching, all fields
    private static UserAgentAnalyzerDirect directAnalyzer;  // No caching, only some fields
    private static UserAgentAnalyzer       plainAnalyzer;   // Caching, all fields, no handlers

    @BeforeAll
    static void createAnalyzers() {
        analyzer = UserAgentAnalyzer
            .newBuilder()
            .hideMatcherLoadStats()
            .withCacheInstantiator(CACHE)
            .withCache(1000)
            .addHandlerBeforeAnalysis(BEFORE)
            .addHandlerAfterAnalysis(AFTER)
            .build();

        directAnalyzer = UserAgentAnalyzerDirect
            .newBuilder()
            .hideMatcherLoadStats()
            .withField(DEVICE_CLASS)
            .withField(AGENT_NAME_VERSION)
            .addHandlerBeforeAnalysis(new MyAppHandler())
            .addHandlerAfterAnalysis(new AcmeHandler())
            .build();

        plainAnalyzer = UserAgentAnalyzer
            .newBuilder()
            .hideMatcherLoadStats()
            .build();
    }

    private static void assertMyApp(@NonNull String expectedUserAgent, @NonNull UserAgent userAgent) {
        assertEquals(expectedUserAgent,  userAgent.getUserAgentString());
        assertEquals(expectedUserAgent,  userAgent.getValue("Useragent"));
        assertEquals("Phone",            userAgent.getValue(DEVICE_CLASS));
        assertEquals(1000,               userAgent.getConfidence(DEVICE_CLASS));
        assertEquals("MyApp 1.2.3",      userAgent.getValue(AGENT_NAME_VERSION));
    }

    // ------------------------------------------

    @Test
    void handlerBeforeAnalysisDoesNotFillTheCache() {
        int cacheSize = CACHE.size();
        int calls     = BEFORE.calls.get();
        int results   = BEFORE.results.get();

        for (int i = 0; i < 1000; i++) {
            String userAgent = myAppUserAgent();
            ImmutableUserAgent result = analyzer.parse(userAgent);
            assertMyApp(userAgent, result);
            // All other fields are completed just like in a normal result.
            assertEquals("Android",     result.getValue(OPERATING_SYSTEM_NAME));
            assertEquals("MyApp",       result.getValue(AGENT_NAME));
            assertEquals("1.2.3",       result.getValue(AGENT_VERSION));
            assertEquals("1",           result.getValue(AGENT_VERSION_MAJOR));
            assertEquals("MyApp 1",     result.getValue(AGENT_NAME_VERSION_MAJOR));
            assertEquals("Special",     result.getValue("AgentClass"));
            // With all fields the list of fields of a result depends on the useragent; all standard fields are there.
            assertTrue(result.getAvailableFieldNamesSorted().containsAll(UserAgent.STANDARD_FIELDS));
            assertFalse(result.hasSyntaxError());
        }

        assertEquals(cacheSize,      CACHE.size(),          "The handled useragents must not be cached");
        assertEquals(calls + 1000,   BEFORE.calls.get(),    "The handler must be called exactly once per parse");
        assertEquals(results + 1000, BEFORE.results.get());
    }

    @Test
    void handlerBeforeAnalysisIsCalledBeforeTheCache() {
        int calls = BEFORE.calls.get();

        ImmutableUserAgent first  = analyzer.parse(FIREFOX);
        ImmutableUserAgent second = analyzer.parse(FIREFOX);
        assertSame(first, second, "The second parse must come from the cache");
        assertEquals(plainAnalyzer.parse(FIREFOX), first, "A useragent that is not handled is analyzed normally");
        assertEquals(calls + 2, BEFORE.calls.get(), "The handler is also called when the result is in the cache");
    }

    @Test
    void handlerBeforeAnalysisWithAllParseMethods() {
        for (AbstractUserAgentAnalyzerDirect uaa : List.of(analyzer, directAnalyzer)) {
            String userAgent = myAppUserAgent();

            assertMyApp(userAgent, uaa.parse(userAgent));
            assertMyApp(userAgent, uaa.parse(Collections.singletonMap(USERAGENT_HEADER, userAgent)));
            assertMyApp(userAgent, uaa.parse(Collections.singletonMap("user-agent", userAgent)));
            assertMyApp(userAgent, uaa.parse(Collections.singletonMap("USER-AGENT", userAgent)));
            assertMyApp(userAgent, uaa.parse(new MutableUserAgent(userAgent)));

            Map<String, String> headers = new HashMap<>();
            headers.put("user-AGENT", userAgent);
            headers.put("Sec-Ch-Ua-Platform", "\"Android\"");
            ImmutableUserAgent result = uaa.parse(headers);
            assertMyApp(userAgent, result);
            assertEquals("\"Android\"", result.getHeaders().get("sec-ch-ua-platform"),
                "The result must have all headers of the request");
        }

        // Exactly one call per parse, also for a useragent that is not handled (and not in the cache).
        for (AbstractUserAgentAnalyzerDirect uaa : List.of(analyzer, directAnalyzer)) {
            String userAgent = "Mozilla/5.0 (X11; Linux x86_64; rv:" + ThreadLocalRandom.current().nextInt(1000) +
                ".0) Gecko/20100101 Firefox/129.0";
            CountingHandler counter = new CountingHandler(new MyAppHandler());
            AbstractUserAgentAnalyzerDirect counted = uaa == analyzer ?
                UserAgentAnalyzer.newBuilder().hideMatcherLoadStats().withCache(100).addHandlerBeforeAnalysis(counter).build() :
                UserAgentAnalyzerDirect.newBuilder().hideMatcherLoadStats().addHandlerBeforeAnalysis(counter).build();
            counted.parse(userAgent);
            counted.parse(Collections.singletonMap(USERAGENT_HEADER, userAgent + " a"));
            counted.parse(Collections.singletonMap("USER-AGENT", userAgent + " b"));
            counted.parse(new MutableUserAgent(userAgent + " c"));
            Map<String, String> headers = new HashMap<>();
            headers.put(USERAGENT_HEADER, userAgent + " d");
            headers.put("Sec-Ch-Ua-Platform", "\"Linux\"");
            counted.parse(headers);
            assertEquals(5, counter.calls.get());
            assertEquals(0, counter.results.get());
        }
    }

    @Test
    void handlerBeforeAnalysisGetsTheWantedFields() {
        String userAgent = myAppUserAgent();
        ImmutableUserAgent result = directAnalyzer.parse(userAgent);
        assertMyApp(userAgent, result);
        assertEquals(directAnalyzer.parse(CHROME).getAvailableFieldNamesSorted(), result.getAvailableFieldNamesSorted(),
            "A handled result must have the same fields as a normal result");
        assertFalse(result.getAvailableFieldNamesSorted().contains(OPERATING_SYSTEM_NAME));
    }

    @Test
    void handlerAfterAnalysisReplacesTheResultAndIsCached() {
        String userAgent = "Acme Crawler " + UUID.randomUUID();
        int calls   = AFTER.calls.get();
        int results = AFTER.results.get();

        ImmutableUserAgent result = analyzer.parse(userAgent);
        assertEquals(userAgent,        result.getUserAgentString());
        assertEquals("Robot",          result.getValue(DEVICE_CLASS));
        assertEquals("AcmeBot",        result.getValue(AGENT_NAME));
        assertSame(result, analyzer.parse(userAgent), "The result of a handler after the analysis must be cached");
        assertEquals(calls + 1,        AFTER.calls.get());
        assertEquals(results + 1,      AFTER.results.get());

        // All other fields are the result of the built-in analysis.
        ImmutableUserAgent analysisResult = plainAnalyzer.parse(userAgent);
        for (String fieldName : analysisResult.getAvailableFieldNamesSorted()) {
            if (!fieldName.equals(DEVICE_CLASS) && !fieldName.equals(AGENT_NAME)) {
                assertEquals(analysisResult.get(fieldName), result.get(fieldName), fieldName);
            }
        }

        // A useragent that is not handled keeps the result of the built-in analysis.
        String other = CHROME + " " + UUID.randomUUID();
        assertEquals(plainAnalyzer.parse(other), analyzer.parse(other));
        assertEquals(calls + 2,        AFTER.calls.get());
        assertEquals(results + 1,      AFTER.results.get());

        // The direct analyzer (no cache) does the same.
        result = directAnalyzer.parse(userAgent);
        assertEquals("Robot",          result.getValue(DEVICE_CLASS));
        assertEquals(directAnalyzer.parse(CHROME).getAvailableFieldNamesSorted(), result.getAvailableFieldNamesSorted());
    }

    @Test
    void handlerBeforeAnalysisWinsFromHandlerAfterAnalysis() {
        int afterCalls = AFTER.calls.get();
        String userAgent = myAppUserAgent();
        assertMyApp(userAgent, analyzer.parse(userAgent));
        assertEquals(afterCalls, AFTER.calls.get(), "A handled request must not be analyzed");
    }

    @Test
    void handlersAreCalledInOrder() {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        UserAgentAnalyzer uaa = UserAgentAnalyzer
            .newBuilder()
            .hideMatcherLoadStats()
            .withField(DEVICE_CLASS)
            .addHandlerBeforeAnalysis(request -> {
                calls.add("before 1");
                return null;
            })
            .addHandlerBeforeAnalysis(request -> {
                calls.add("before 2");
                return new MyAppHandler().handle(request);
            })
            .addHandlerBeforeAnalysis(request -> {
                calls.add("before 3");
                return null;
            })
            .addHandlerAfterAnalysis(request -> {
                calls.add("after 1");
                assertNotNull(request.getAnalysisResult());
                return null;
            })
            .addHandlerAfterAnalysis(request -> {
                calls.add("after 2");
                MutableUserAgent result = new MutableUserAgent(request.getAnalysisResult());
                result.setForced(DEVICE_CLASS, "Changed", 1000);
                return result;
            })
            .addHandlerAfterAnalysis(request -> {
                calls.add("after 3");
                return null;
            })
            .build();

        String userAgent = myAppUserAgent();
        assertEquals("Phone", uaa.parse(userAgent).getValue(DEVICE_CLASS));
        assertEquals(List.of("before 1", "before 2"), calls);

        calls.clear();
        assertEquals("Changed", uaa.parse(CHROME).getValue(DEVICE_CLASS));
        assertEquals(List.of("before 1", "before 2", "before 3", "after 1", "after 2"), calls);
    }

    @Test
    void handlerBeforeAnalysisGetsNoAnalysisResult() {
        List<String> userAgents = Collections.synchronizedList(new ArrayList<>());
        UserAgentAnalyzer uaa = UserAgentAnalyzer
            .newBuilder()
            .hideMatcherLoadStats()
            .withField(DEVICE_CLASS)
            .addHandlerBeforeAnalysis(request -> {
                assertNull(request.getAnalysisResult());
                userAgents.add(String.valueOf(request.getUserAgentString()));
                if (request.getUserAgentString() != null) {
                    return null;
                }
                // Also a request without a useragent can be handled
                MutableUserAgent result = request.newResult();
                result.set(DEVICE_CLASS, "Nothing at all", 1000);
                return result;
            })
            .build();

        ImmutableUserAgent result = uaa.parse((String) null);
        assertNull(result.getUserAgentString());
        assertEquals("Nothing at all", result.getValue(DEVICE_CLASS));
        assertEquals("Nothing at all", uaa.parse((MutableUserAgent) null).getValue(DEVICE_CLASS));
        assertEquals("Nothing at all", uaa.parse(Collections.singletonMap(USERAGENT_HEADER, null)).getValue(DEVICE_CLASS));
        assertEquals("Desktop", uaa.parse(FIREFOX).getValue(DEVICE_CLASS));
        assertEquals(List.of("null", "null", "null", FIREFOX), userAgents);
    }

    @Test
    void theResultIsAlwaysAboutTheRequest() {
        UserAgentAnalyzer uaa = UserAgentAnalyzer
            .newBuilder()
            .hideMatcherLoadStats()
            .withField(DEVICE_CLASS)
            .addHandlerBeforeAnalysis(request -> {
                // A result that was not created with request.newResult()
                MutableUserAgent result = new MutableUserAgent("Something else");
                result.addHeader("X-Something", "else");
                result.set(DEVICE_CLASS, "Phone", 1000);
                return result;
            })
            .build();

        Map<String, String> headers = new HashMap<>();
        headers.put(USERAGENT_HEADER, FIREFOX);
        headers.put("Sec-Ch-Ua-Platform", "\"Windows\"");
        ImmutableUserAgent result = uaa.parse(headers);
        assertEquals(FIREFOX, result.getUserAgentString());
        assertEquals(headers, new HashMap<>(result.getHeaders()));
        assertEquals("Phone", result.getValue(DEVICE_CLASS));
    }

    @Test
    void exceptionsArePassedOn() {
        AtomicInteger afterCalls = new AtomicInteger();
        UserAgentAnalyzer uaa = UserAgentAnalyzer
            .newBuilder()
            .hideMatcherLoadStats()
            .withField(DEVICE_CLASS)
            .withCache(100)
            .addHandlerBeforeAnalysis(request -> {
                if ("Boom before".equals(request.getUserAgentString())) {
                    throw new IllegalStateException("Before");
                }
                return null;
            })
            .addHandlerAfterAnalysis(request -> {
                afterCalls.incrementAndGet();
                if ("Boom after".equals(request.getUserAgentString())) {
                    throw new IllegalStateException("After");
                }
                return null;
            })
            .build();

        assertEquals("Before", assertThrows(IllegalStateException.class, () -> uaa.parse("Boom before")).getMessage());
        assertEquals("After",  assertThrows(IllegalStateException.class, () -> uaa.parse("Boom after")).getMessage());
        // A failed parse is not cached.
        assertThrows(IllegalStateException.class, () -> uaa.parse("Boom after"));
        assertEquals(2, afterCalls.get());
        // And the analyzer still works.
        assertEquals("Desktop", uaa.parse(FIREFOX).getValue(DEVICE_CLASS));
    }

    @Test
    void builderChecks() {
        UserAgentAnalyzer.UserAgentAnalyzerBuilder builder = UserAgentAnalyzer.newBuilder().hideMatcherLoadStats();
        assertThrows(NullPointerException.class, () -> builder.addHandlerBeforeAnalysis(null));
        assertThrows(NullPointerException.class, () -> builder.addHandlerAfterAnalysis(null));
        builder.withField(DEVICE_CLASS).build();
        UserAgentHandler handler = new MyAppHandler();
        assertThrows(IllegalStateException.class, () -> builder.addHandlerBeforeAnalysis(handler));
        assertThrows(IllegalStateException.class, () -> builder.addHandlerAfterAnalysis(handler));
    }

    @Test
    void cloneKeepsTheHandlers() {
        UserAgentAnalyzerDirect clone = directAnalyzer.cloneWithSharedAnalyzerConfig(false, true);
        String userAgent = myAppUserAgent();
        assertMyApp(userAgent, clone.parse(userAgent));
        assertEquals("Robot", clone.parse("Acme Crawler").getValue(DEVICE_CLASS));
    }

    @Test
    void manyThreads() throws Exception {
        int threads    = 8;
        int iterations = 2000;
        int calls      = BEFORE.calls.get();
        int results    = BEFORE.results.get();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                futures.add(executor.submit(() -> {
                    int handled = 0;
                    for (int i = 0; i < iterations; i++) {
                        if (i % 4 == 0) {
                            assertEquals(plainAnalyzer.parse(CHROME), analyzer.parse(CHROME));
                        } else {
                            String userAgent = myAppUserAgent();
                            assertMyApp(userAgent, analyzer.parse(userAgent));
                            handled++;
                        }
                    }
                    return handled;
                }));
            }
            int handled = 0;
            for (Future<Integer> future : futures) {
                handled += future.get();
            }
            assertEquals(calls + threads * iterations, BEFORE.calls.get());
            assertEquals(results + handled,            BEFORE.results.get());
        } finally {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------

    @NonNull
    private static UserAgentAnalyzer analyzerForSerialization() {
        return UserAgentAnalyzer
            .newBuilder()
            .hideMatcherLoadStats()
            .withField(DEVICE_CLASS)
            .withField(AGENT_NAME_VERSION)
            .addHandlerBeforeAnalysis(new MyAppHandler())
            .addHandlerAfterAnalysis(new AcmeHandler())
            .build();
    }

    private static void assertHandlersWork(@NonNull UserAgentAnalyzer uaa) {
        String userAgent = myAppUserAgent();
        assertMyApp(userAgent, uaa.parse(userAgent));
        assertEquals("Robot",   uaa.parse("Acme Crawler").getValue(DEVICE_CLASS));
        assertEquals("Desktop", uaa.parse(FIREFOX).getValue(DEVICE_CLASS));
    }

    @Test
    void javaSerialization() throws IOException, ClassNotFoundException {
        UserAgentAnalyzer uaa = analyzerForSerialization();
        assertHandlersWork(uaa);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(uaa);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertHandlersWork((UserAgentAnalyzer) in.readObject());
        }
    }

    @NonNull
    private static Kryo kryo() {
        Kryo kryo = new Kryo();
        UserAgentAnalyzer.configureKryo(kryo);
        kryo.register(MyAppHandler.class);
        kryo.register(AcmeHandler.class);
        kryo.setRegistrationRequired(true);
        return kryo;
    }

    @Test
    void kryoSerialization() {
        UserAgentAnalyzer uaa = analyzerForSerialization();
        assertHandlersWork(uaa);

        ByteBufferOutput output = new ByteBufferOutput(1_000_000, -1);
        kryo().writeClassAndObject(output, uaa);
        ByteBuffer buffer = output.getByteBuffer();
        byte[] bytes = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(bytes);

        assertHandlersWork((UserAgentAnalyzer) kryo().readClassAndObject(new ByteBufferInput(bytes)));
    }
}
