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

import nl.basjes.parse.useragent.UserAgent.ImmutableUserAgent;
import nl.basjes.parse.useragent.config.TestCase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A single analyzer instance must give exactly the same results when it is used by many threads at the same time.
 */
class TestConcurrentParsing {

    private static final Logger LOG = LogManager.getLogger(TestConcurrentParsing.class);

    private static final int THREADS = Math.max(4, Math.min(8, Runtime.getRuntime().availableProcessors()));

    // A (deterministic) sample of the testcases is enough to trigger the concurrency problems.
    private static final int MAX_INPUTS = 1000;

    // The inputs and the expected results (calculated single threaded with a separate analyzer).
    private static final List<Map<String, String>> INPUTS = new ArrayList<>();
    private static final List<ImmutableUserAgent>  EXPECTED = new ArrayList<>();

    @BeforeAll
    static void calculateExpectations() {
        UserAgentAnalyzer reference = UserAgentAnalyzer
            .newBuilder()
            .withoutCache()
            .immediateInitialization()
            .keepTests()
            .hideMatcherLoadStats()
            .build();

        LinkedHashSet<Map<String, String>> allInputs = new LinkedHashSet<>();
        for (TestCase testCase : reference.getTestCases()) {
            allInputs.add(new TreeMap<>(testCase.getHeaders()));
        }
        List<Map<String, String>> inputs = new ArrayList<>();
        int step = Math.max(1, allInputs.size() / MAX_INPUTS);
        int count = 0;
        for (Map<String, String> input : allInputs) {
            if (count++ % step == 0) {
                inputs.add(input);
            }
        }

        // Also some useragents that trigger the special code paths.
        StringBuilder tooLong = new StringBuilder("Mozilla/5.0 ");
        while (tooLong.length() < 3000) {
            tooLong.append("(Foo; Bar) ");
        }
        inputs.add(Collections.singletonMap("User-Agent", tooLong.toString()));
        inputs.add(Collections.singletonMap("User-Agent", ""));
        inputs.add(Collections.singletonMap("User-Agent", "    "));
        inputs.add(Collections.singletonMap("User-Agent", "()()((;;//"));

        INPUTS.addAll(inputs);
        for (Map<String, String> input : INPUTS) {
            EXPECTED.add(reference.parse(input));
        }
        LOG.info("Calculated {} expected results", EXPECTED.size());
    }

    private static String describeDifference(Map<String, String> input, ImmutableUserAgent expected, ImmutableUserAgent actual) {
        StringBuilder sb = new StringBuilder("Different result for ").append(input).append('\n');
        for (String fieldName : expected.getAvailableFieldNamesSorted()) {
            AgentField expectedField = expected.get(fieldName);
            AgentField actualField = actual.get(fieldName);
            if (!expectedField.equals(actualField)) {
                sb.append("- ").append(fieldName)
                  .append(" expected ").append(expectedField)
                  .append(" but got ").append(actualField).append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean sameResult(ImmutableUserAgent expected, ImmutableUserAgent actual) {
        return expected.equals(actual) &&
            expected.hasSyntaxError()    == actual.hasSyntaxError() &&
            expected.hasAmbiguity()      == actual.hasAmbiguity() &&
            expected.getAmbiguityCount() == actual.getAmbiguityCount();
    }

    /**
     * Let many threads parse all inputs (each in a different order) at the same time using the same analyzer.
     * @param analyzer The analyzer under test
     * @param sameOrder If true all threads use the same order (i.e. they all parse the same input at the same time)
     * @param rounds How many times each thread does all inputs
     */
    private static void parseConcurrently(AbstractUserAgentAnalyzer analyzer, boolean sameOrder, int rounds)
        throws Exception {
        Queue<String> errors = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                final long seed = sameOrder ? 42 : thread;
                futures.add(executor.submit(() -> {
                    List<Integer> order = new ArrayList<>();
                    for (int i = 0; i < INPUTS.size(); i++) {
                        order.add(i);
                    }
                    Collections.shuffle(order, new Random(seed));
                    start.await();
                    long parses = 0;
                    for (int round = 0; round < rounds; round++) {
                        for (int index : order) {
                            Map<String, String> input = INPUTS.get(index);
                            ImmutableUserAgent expected = EXPECTED.get(index);
                            ImmutableUserAgent actual;
                            if (input.size() == 1 && input.containsKey("User-Agent") && (index % 2 == 0)) {
                                actual = analyzer.parse(input.get("User-Agent")); // Use both entry points
                            } else {
                                actual = analyzer.parse(input);
                            }
                            parses++;
                            if (!sameResult(expected, actual)) {
                                errors.add(describeDifference(input, expected, actual));
                            }
                        }
                    }
                    return parses;
                }));
            }
            start.countDown();
            long totalParses = 0;
            for (Future<Long> future : futures) {
                totalParses += future.get(10, TimeUnit.MINUTES);
            }
            LOG.info("Did {} parses using {} threads with {} errors.", totalParses, THREADS, errors.size());
        } finally {
            executor.shutdownNow();
        }

        if (!errors.isEmpty()) {
            errors.stream().limit(10).forEach(LOG::error);
        }
        assertEquals(0, errors.size(), "Concurrent parsing produced different results.");
    }

    @Test
    void concurrentParsingWithoutCache() throws Exception {
        UserAgentAnalyzer analyzer = UserAgentAnalyzer
            .newBuilder()
            .withoutCache()
            .immediateInitialization()
            .dropTests()
            .hideMatcherLoadStats()
            .build();
        parseConcurrently(analyzer, false, 1);
    }

    @Test
    void concurrentParsingWithSmallCache() throws Exception {
        // The cache is much smaller than the number of inputs: Hits, misses and evictions all happen concurrently.
        UserAgentAnalyzer analyzer = UserAgentAnalyzer
            .newBuilder()
            .withCache(500)
            .withClientHintsCache(10)
            .immediateInitialization()
            .dropTests()
            .hideMatcherLoadStats()
            .build();
        parseConcurrently(analyzer, false, 2);
    }

    @Test
    void concurrentParsingOfTheSameInputs() throws Exception {
        // All threads parse the same new useragent at the same moment (i.e. concurrent cache misses on the same key).
        UserAgentAnalyzer analyzer = UserAgentAnalyzer
            .newBuilder()
            .withCache(100000)
            .immediateInitialization()
            .dropTests()
            .hideMatcherLoadStats()
            .build();
        parseConcurrently(analyzer, true, 1);
    }

    @Test
    void concurrentLazyInitialization() throws Exception {
        // Nothing has been initialized yet: All threads trigger the (just in time) initialization at the same time.
        UserAgentAnalyzer analyzer = UserAgentAnalyzer
            .newBuilder()
            .withoutCache()
            .delayInitialization()
            .dropTests()
            .hideMatcherLoadStats()
            .build();
        parseConcurrently(analyzer, false, 1);
    }

    @Test
    void concurrentParsingAfterDeserialization() throws Exception {
        UserAgentAnalyzer original = UserAgentAnalyzer
            .newBuilder()
            .withoutCache()
            .immediateInitialization()
            .dropTests()
            .hideMatcherLoadStats()
            .build();
        // Make sure the analyzer has been used before it is serialized.
        original.parse(INPUTS.get(0));

        UserAgentAnalyzer deserialized = deserialize(serialize(original));
        // All transient runtime structures are rebuilt (just in time) while many threads are parsing.
        parseConcurrently(deserialized, false, 1);
    }

    @Test
    void concurrentParsingOfLimitedFields() throws Exception {
        UserAgentAnalyzer reference = UserAgentAnalyzer
            .newBuilder()
            .withoutCache()
            .withFields("DeviceClass", "AgentNameVersionMajor", "OperatingSystemNameVersion")
            .immediateInitialization()
            .dropTests()
            .hideMatcherLoadStats()
            .build();

        Map<Map<String, String>, ImmutableUserAgent> expected = new LinkedHashMap<>();
        for (Map<String, String> input : INPUTS) {
            expected.put(input, reference.parse(input));
        }

        UserAgentAnalyzer analyzer = UserAgentAnalyzer
            .newBuilder()
            .withoutCache()
            .withFields("DeviceClass", "AgentNameVersionMajor", "OperatingSystemNameVersion")
            .immediateInitialization()
            .dropTests()
            .hideMatcherLoadStats()
            .build();

        Queue<String> errors = new ConcurrentLinkedQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                final int offset = thread * (INPUTS.size() / THREADS);
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < INPUTS.size(); i++) {
                        Map<String, String> input = INPUTS.get((i + offset) % INPUTS.size());
                        ImmutableUserAgent actual = analyzer.parse(input);
                        if (!sameResult(expected.get(input), actual)) {
                            errors.add(describeDifference(input, expected.get(input), actual));
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.MINUTES);
            }
        } finally {
            executor.shutdownNow();
        }
        errors.stream().limit(10).forEach(LOG::error);
        assertTrue(errors.isEmpty(), "Concurrent parsing produced different results.");
    }

    private static byte[] serialize(UserAgentAnalyzer analyzer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(analyzer);
        }
        return bytes.toByteArray();
    }

    private static UserAgentAnalyzer deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (UserAgentAnalyzer) in.readObject();
        }
    }
}
