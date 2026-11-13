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

package nl.basjes.parse.useragent.parse;

import nl.basjes.parse.useragent.UserAgent.MutableUserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;
import nl.basjes.parse.useragent.config.TestCase;
import nl.basjes.parse.useragent.parser.UserAgentLexer;
import nl.basjes.parse.useragent.parser.UserAgentParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.dfa.DFA;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UserAgentParserATNSimulator must produce exactly the same as the standard ParserATNSimulator of ANTLR.
 * Because it relies on the internals of ANTLR these tests are also the safety net for an ANTLR upgrade.
 */
class TestUserAgentParserATNSimulator {

    private static final Logger LOG = LogManager.getLogger(TestUserAgentParserATNSimulator.class);

    private static final int NUMBER_OF_DECISIONS = UserAgentParser._ATN.getNumberOfDecisions();
    private static final int MAX_LOOKAHEAD       = UserAgentParserATNSimulator.DEFAULT_MAX_FULL_CONTEXT_LOOKAHEAD;
    private static final int MAX_USERAGENT_LENGTH = 2048; // The default of the analyzer: longer ones are never parsed.

    private static final List<String>      USERAGENTS = new ArrayList<>();
    private static final List<ParseResult> EXPECTED   = new ArrayList<>();

    /**
     * Everything a parse produces: the parse tree and everything that was reported to the error listener
     * (except the ATN configurations which are not available for a cached outcome).
     */
    private static final class ParseResult {
        private final String       tree;
        private final List<String> events;
        private final int          fullContextLookahead;
        private final int          cacheHits;
        private final int          cacheMisses;

        ParseResult(@NonNull String tree, @NonNull List<String> events, int fullContextLookahead, int cacheHits, int cacheMisses) {
            this.tree                 = tree;
            this.events               = events;
            this.fullContextLookahead = fullContextLookahead;
            this.cacheHits            = cacheHits;
            this.cacheMisses          = cacheMisses;
        }

        // The statistics are deliberately NOT part of equals and hashCode.
        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof ParseResult)) {
                return false;
            }
            ParseResult other = (ParseResult) o;
            return tree.equals(other.tree) && events.equals(other.events);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tree, events);
        }

        @Override
        @NonNull
        public String toString() {
            return "ParseResult{tree=" + tree + ", events=" + events + '}';
        }
    }

    private static final class RecordingErrorListener extends BaseErrorListener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void syntaxError(@Nullable Recognizer<?, ?> recognizer,
                                @Nullable Object offendingSymbol,
                                int line,
                                int charPositionInLine,
                                @Nullable String msg,
                                @Nullable RecognitionException e) {
            events.add("SyntaxError " + line + ":" + charPositionInLine + " " + msg);
        }

        @Override
        public void reportAmbiguity(@Nullable Parser recognizer,
                                    @NonNull DFA dfa,
                                    int startIndex,
                                    int stopIndex,
                                    boolean exact,
                                    @Nullable BitSet ambigAlts,
                                    @Nullable ATNConfigSet configs) {
            events.add("Ambiguity decision=" + dfa.decision + " " + startIndex + ".." + stopIndex +
                " exact=" + exact + " alternatives=" + ambigAlts);
        }

        @Override
        public void reportAttemptingFullContext(@Nullable Parser recognizer,
                                                @NonNull DFA dfa,
                                                int startIndex,
                                                int stopIndex,
                                                @Nullable BitSet conflictingAlts,
                                                @Nullable ATNConfigSet configs) {
            events.add("AttemptingFullContext decision=" + dfa.decision + " " + startIndex + ".." + stopIndex +
                " alternatives=" + conflictingAlts);
        }

        @Override
        public void reportContextSensitivity(@Nullable Parser recognizer,
                                             @NonNull DFA dfa,
                                             int startIndex,
                                             int stopIndex,
                                             int prediction,
                                             @Nullable ATNConfigSet configs) {
            events.add("ContextSensitivity decision=" + dfa.decision + " " + startIndex + ".." + stopIndex +
                " prediction=" + prediction);
        }
    }

    @FunctionalInterface
    private interface SimulatorFactory {
        /**
         * @return The simulator to use, null to use the standard ParserATNSimulator.
         */
        @Nullable
        ParserATNSimulator create(@NonNull UserAgentParser parser);
    }

    @NonNull
    private static ParseResult parse(@NonNull String useragent, @NonNull SimulatorFactory simulatorFactory) {
        UserAgentLexer lexer = new UserAgentLexer(CharStreams.fromString(useragent));
        lexer.removeErrorListeners();
        UserAgentParser parser = new UserAgentParser(new CommonTokenStream(lexer));
        ParserATNSimulator simulator = simulatorFactory.create(parser);
        if (simulator != null) {
            parser.setInterpreter(simulator);
        }
        RecordingErrorListener listener = new RecordingErrorListener();
        parser.removeErrorListeners();
        parser.addErrorListener(listener);

        String tree = parser.userAgent().toStringTree(parser);

        if (simulator instanceof UserAgentParserATNSimulator) {
            UserAgentParserATNSimulator ourSimulator = (UserAgentParserATNSimulator) simulator;
            return new ParseResult(tree, listener.events,
                ourSimulator.getFullContextLookahead(), ourSimulator.getCacheHits(), ourSimulator.getCacheMisses());
        }
        return new ParseResult(tree, listener.events, -1, 0, 0);
    }

    @NonNull
    private static ParseResult parseStandard(@NonNull String useragent) {
        return parse(useragent, parser -> null);
    }

    @NonNull
    private static ParseResult parse(@NonNull String useragent, @NonNull FullContextPredictionCache cache, int maxLookahead) {
        return parse(useragent, parser -> new UserAgentParserATNSimulator(parser, cache, maxLookahead));
    }

    @NonNull
    private static FullContextPredictionCache newCache() {
        return new FullContextPredictionCache(
            NUMBER_OF_DECISIONS,
            FullContextPredictionCache.DEFAULT_MAX_NODES,
            FullContextPredictionCache.DEFAULT_MAX_PATH_LENGTH);
    }

    @NonNull
    private static String repeatUpTo(@NonNull String pattern, int length) {
        StringBuilder sb = new StringBuilder(length + pattern.length());
        while (sb.length() < length) {
            sb.append(pattern);
        }
        return sb.substring(0, length);
    }

    // Useragents for which (without a limit) the full context predictions take seconds of CPU time.
    // These are the strings the parser gets from the analyzer (i.e. after the cleanup).
    @NonNull
    private static List<String> pathologicalUseragents() {
        List<String> useragents = new ArrayList<>();
        useragents.add(("invalid../../../../../../../../../../etc/passwd" + repeatUpTo("/.", MAX_USERAGENT_LENGTH))
            .substring(0, MAX_USERAGENT_LENGTH));
        useragents.add(repeatUpTo("//./.", MAX_USERAGENT_LENGTH));
        useragents.add(repeatUpTo("aa/a/", MAX_USERAGENT_LENGTH));
        useragents.add(repeatUpTo("a-a/", MAX_USERAGENT_LENGTH));
        useragents.add(repeatUpTo("/.--", MAX_USERAGENT_LENGTH));
        List<String> cleaned = new ArrayList<>();
        for (String useragent : useragents) {
            cleaned.add(EvilManualUseragentStringHacks.fixIt(new MutableUserAgent(useragent)));
        }
        return cleaned;
    }

    @BeforeAll
    static void calculateExpectations() {
        UserAgentAnalyzer analyzer = UserAgentAnalyzer
            .newBuilder()
            .keepTests()
            .delayInitialization()
            .withoutCache()
            .hideMatcherLoadStats()
            .build();

        // What the parser gets from the analyzer and some variants that exercise the error handling of the parser.
        Set<String> useragents = new LinkedHashSet<>();
        Random random = new Random(42);
        int count = 0;
        for (TestCase testCase : analyzer.getTestCases()) {
            String useragent = testCase.getUserAgent();
            if (useragent == null || useragent.length() > MAX_USERAGENT_LENGTH) {
                continue;
            }
            String cleaned = EvilManualUseragentStringHacks.fixIt(new MutableUserAgent(useragent));
            if (cleaned == null) {
                continue;
            }
            useragents.add(cleaned);
            if (count++ % 3 == 0) {
                useragents.add(cleaned.substring(0, random.nextInt(cleaned.length() + 1)));
                useragents.add(cleaned.toUpperCase(Locale.ROOT));
            }
        }
        USERAGENTS.addAll(useragents);

        for (String useragent : USERAGENTS) {
            EXPECTED.add(parseStandard(useragent));
        }
        LOG.info("Calculated the expected parse results of {} useragents.", USERAGENTS.size());
    }

    @Test
    void identicalToStandardSimulator() {
        FullContextPredictionCache cache = newCache();

        // First with an empty cache: everything is computed.
        long coldHits = 0;
        long coldMisses = 0;
        int maxLookahead = 0;
        String maxLookaheadUseragent = "";
        for (int i = 0; i < USERAGENTS.size(); i++) {
            String useragent = USERAGENTS.get(i);
            ParseResult result = parse(useragent, cache, MAX_LOOKAHEAD);
            assertEquals(EXPECTED.get(i), result, "Different result (empty cache) for: " + useragent);
            coldHits += result.cacheHits;
            coldMisses += result.cacheMisses;
            if (result.fullContextLookahead > maxLookahead) {
                maxLookahead = result.fullContextLookahead;
                maxLookaheadUseragent = useragent;
            }
        }
        int sizeAfterFirstRound = cache.size();

        // Then again: now (almost) everything comes from the cache.
        long warmHits = 0;
        long warmMisses = 0;
        for (int i = 0; i < USERAGENTS.size(); i++) {
            String useragent = USERAGENTS.get(i);
            ParseResult result = parse(useragent, cache, MAX_LOOKAHEAD);
            assertEquals(EXPECTED.get(i), result, "Different result (filled cache) for: " + useragent);
            warmHits += result.cacheHits;
            warmMisses += result.cacheMisses;
        }

        LOG.info("Useragents: {}; Empty cache: {} hits, {} misses; Filled cache: {} hits, {} misses; Cache size: {} nodes",
            USERAGENTS.size(), coldHits, coldMisses, warmHits, warmMisses, sizeAfterFirstRound);
        LOG.info("Highest full context lookahead: {} for \"{}\"", maxLookahead, maxLookaheadUseragent);

        // The comparison is only meaningful if none of these useragents reached the limit.
        assertTrue(maxLookahead <= MAX_LOOKAHEAD / 2,
            "The full context lookahead (" + maxLookahead + ") of \"" + maxLookaheadUseragent + "\" is too close to the limit.");

        assertTrue(coldMisses > 0, "There should have been full context predictions");
        assertTrue(warmHits > 0, "The cache was not used");
        assertTrue(warmMisses * 20 < coldMisses, "Too many cache misses: " + warmMisses + " (first round: " + coldMisses + ")");
        assertEquals(sizeAfterFirstRound, cache.size(), "Nothing new should have been stored in the second round");
        assertEquals(0, cache.getResets());
    }

    @Test
    void identicalWhenUsedConcurrently() throws InterruptedException {
        FullContextPredictionCache cache = newCache();
        int threads = Math.max(4, Math.min(8, Runtime.getRuntime().availableProcessors()));
        CountDownLatch start = new CountDownLatch(1);
        Queue<String> failures = new ConcurrentLinkedQueue<>();

        List<Thread> workers = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < USERAGENTS.size(); i++) {
                order.add(i);
            }
            if (thread > 0) { // The first one goes in the same order as the others are started.
                Collections.shuffle(order, new Random(thread));
            }
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i : order) {
                        ParseResult result = parse(USERAGENTS.get(i), cache, MAX_LOOKAHEAD);
                        if (!EXPECTED.get(i).equals(result)) {
                            failures.add(USERAGENTS.get(i));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.add("Interrupted");
                } catch (Throwable e) { // An Error would otherwise silently end the thread.
                    failures.add("Thrown: " + e);
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }
        assertTrue(failures.isEmpty(), "Different results for " + failures.size() + " parses like: " + failures.peek());
    }

    @Test
    void identicalWhenTheCacheIsDiscardedAllTheTime() {
        FullContextPredictionCache cache =
            new FullContextPredictionCache(NUMBER_OF_DECISIONS, 50, FullContextPredictionCache.DEFAULT_MAX_PATH_LENGTH);
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < USERAGENTS.size(); i++) {
                assertEquals(EXPECTED.get(i), parse(USERAGENTS.get(i), cache, MAX_LOOKAHEAD),
                    "Different result for: " + USERAGENTS.get(i));
            }
        }
        assertTrue(cache.getResets() > 0, "The cache should have been discarded (many times)");
        assertTrue(cache.size() <= 50 + FullContextPredictionCache.DEFAULT_MAX_PATH_LENGTH, "Too big: " + cache.size());
    }

    @Test
    void theLimitDoesNotDependOnTheCache() {
        // For the useragents with the most full context lookahead: Stop at various limits and
        // check that the outcome is the same with an empty cache and with a cache that has all outcomes.
        int checked = 0;
        for (int i = 0; i < USERAGENTS.size() && checked < 100; i++) {
            String useragent = USERAGENTS.get(i);
            FullContextPredictionCache filledCache = newCache();
            ParseResult unlimited = parse(useragent, filledCache, MAX_LOOKAHEAD); // Also fills the cache
            int lookahead = unlimited.fullContextLookahead;
            if (lookahead < 10) {
                continue;
            }
            checked++;
            for (int limit : new int[]{0, 1, 2, lookahead / 3, lookahead / 2, lookahead - 1, lookahead}) {
                ParseResult computed = parse(useragent, newCache(), limit);
                ParseResult cached   = parse(useragent, filledCache, limit);
                assertEquals(computed, cached, "Limit " + limit + " (of " + lookahead + ") changed the result of: " + useragent);
                if (limit < lookahead) {
                    assertEquals(limit + 1, computed.fullContextLookahead, "Did not stop at limit " + limit + ": " + useragent);
                    assertEquals(limit + 1, cached.fullContextLookahead, "Did not stop at limit " + limit + ": " + useragent);
                } else {
                    assertEquals(EXPECTED.get(i), computed, "Limit " + limit + " was not reached: " + useragent);
                }
            }
        }
        assertTrue(checked > 10, "Not enough useragents with full context lookahead were found: " + checked);
    }

    @Test
    void pathologicalUseragentsAreLimited() {
        for (String useragent : pathologicalUseragents()) {
            FullContextPredictionCache cache = newCache();
            ParseResult first  = parse(useragent, cache, MAX_LOOKAHEAD);
            ParseResult second = parse(useragent, cache, MAX_LOOKAHEAD);
            ParseResult other  = parse(useragent, newCache(), MAX_LOOKAHEAD);

            assertEquals(MAX_LOOKAHEAD + 1, first.fullContextLookahead, "The limit was not reached for " + useragent);
            assertEquals(MAX_LOOKAHEAD + 1, second.fullContextLookahead, "The limit was not reached for " + useragent);
            assertEquals(first, second, "The result depends on the cache for " + useragent);
            assertEquals(first, other, "The result depends on the cache for " + useragent);
        }
    }
}
