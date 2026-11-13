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

package nl.basjes.parse.useragent.benchmarks;

import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;
import nl.basjes.parse.useragent.config.TestCase;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Measures the throughput of a SINGLE analyzer instance that is shared by many threads at the same time.
 * All useragents of all testcases are used (each thread starts at a different position).
 */
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class MultiThreadedBenchmarks {

    @State(Scope.Benchmark)
    public static class SharedAnalyzer {
        UserAgentAnalyzer uncached;
        UserAgentAnalyzer cached;
        String[] userAgents;

        @Setup(Level.Trial)
        public void setup() {
            uncached = UserAgentAnalyzer.newBuilder()
                .withoutCache()
                .immediateInitialization()
                .keepTests()
                .hideMatcherLoadStats()
                .build();
            userAgents = uncached.getTestCases()
                .stream()
                .map(TestCase::getUserAgent)
                .filter(Objects::nonNull)
                .distinct()
                .toArray(String[]::new);

            cached = UserAgentAnalyzer.newBuilder()
                .withCache(userAgents.length * 2)
                .immediateInitialization()
                .dropTests()
                .hideMatcherLoadStats()
                .build();
            for (String userAgent : userAgents) {
                cached.parse(userAgent);
            }
        }
    }

    @State(Scope.Thread)
    public static class Position {
        private int next;

        @Setup(Level.Trial)
        public void setup(SharedAnalyzer shared) {
            next = ThreadLocalRandom.current().nextInt(shared.userAgents.length);
        }

        String nextUserAgent(SharedAnalyzer shared) {
            String userAgent = shared.userAgents[next];
            next++;
            if (next == shared.userAgents.length) {
                next = 0;
            }
            return userAgent;
        }
    }

    @Benchmark
    @Threads(1)
    public UserAgent uncachedThreads01(SharedAnalyzer shared, Position position) {
        return shared.uncached.parse(position.nextUserAgent(shared));
    }

    @Benchmark
    @Threads(2)
    public UserAgent uncachedThreads02(SharedAnalyzer shared, Position position) {
        return shared.uncached.parse(position.nextUserAgent(shared));
    }

    @Benchmark
    @Threads(4)
    public UserAgent uncachedThreads04(SharedAnalyzer shared, Position position) {
        return shared.uncached.parse(position.nextUserAgent(shared));
    }

    @Benchmark
    @Threads(8)
    public UserAgent uncachedThreads08(SharedAnalyzer shared, Position position) {
        return shared.uncached.parse(position.nextUserAgent(shared));
    }

    @Benchmark
    @Threads(Threads.MAX)
    public UserAgent uncachedThreadsMax(SharedAnalyzer shared, Position position) {
        return shared.uncached.parse(position.nextUserAgent(shared));
    }

    @Benchmark
    @Threads(Threads.MAX)
    public UserAgent cachedThreadsMax(SharedAnalyzer shared, Position position) {
        return shared.cached.parse(position.nextUserAgent(shared));
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(MultiThreadedBenchmarks.class.getSimpleName())
            .build();

        new Runner(opt).run();
    }
}
