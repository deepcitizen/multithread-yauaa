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

import nl.basjes.parse.useragent.parse.FullContextPredictionCache.Outcome;
import nl.basjes.parse.useragent.parser.UserAgentParser;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;

/**
 * The ATN simulator that is used to parse the useragents.
 * <p>
 * The useragent grammar is ambiguous and in the default (LL) prediction mode ANTLR resolves every conflict it finds
 * using a full context prediction. These are expensive (about 40% of the total analysis time) and may look ahead
 * until the end of the input. These full context predictions are also what determines the ambiguity that is
 * reported by {@link nl.basjes.parse.useragent.UserAgent#getAmbiguityCount()}.
 * <p>
 * Compared to the standard ParserATNSimulator (in the default LL prediction mode) this one does two things:
 * <ol>
 *     <li>The outcome of every full context prediction is memoized in a (shared, threadsafe and bounded)
 *     {@link FullContextPredictionCache}. A cached outcome is replayed exactly: the same alternative is predicted and
 *     the same ambiguity (or context sensitivity) is reported. So the parse tree and the ambiguity information are
 *     identical to what the standard simulator produces.</li>
 *     <li>The total amount of full context lookahead of a single parse is limited. For some (malicious) inputs the
 *     full context predictions make the parse time quadratic: a single useragent of 2KB took about 15 seconds of
 *     CPU time. When the limit is reached the running full context prediction is stopped and (just like in the SLL
 *     prediction mode) the lowest of the conflicting alternatives is predicted. The rest of that useragent is parsed
 *     using only SLL predictions (no more full context predictions and no more reported ambiguities).
 *     So the worst case does not depend on the length of the input anymore.
 *     The limit is far above what any normal useragent needs. A memoized outcome counts for exactly the same
 *     amount of lookahead as the actual prediction, so the result of a parse never depends on what is in the cache.
 *     </li>
 * </ol>
 * IMPORTANT: This relies on the protected internals of the ParserATNSimulator of the ANTLR version that is used.
 * The unit tests verify for all test useragents that the results are identical to the standard ParserATNSimulator.
 * <p>
 * An instance is used for a single parse; it is not threadsafe (just like the standard ParserATNSimulator).
 */
final class UserAgentParserATNSimulator extends ParserATNSimulator {

    /**
     * The default maximum amount of full context lookahead (the number of full context reach steps) of a parse.
     * Of all the test useragents the highest is about 450; most need less than 10.
     */
    static final int DEFAULT_MAX_FULL_CONTEXT_LOOKAHEAD = 2000;

    private static final FullContextPredictionCache SHARED_CACHE = new FullContextPredictionCache(
        UserAgentParser._ATN.getNumberOfDecisions(),
        FullContextPredictionCache.DEFAULT_MAX_NODES,
        FullContextPredictionCache.DEFAULT_MAX_PATH_LENGTH);

    // Returned instead of the (expensive) full context start state. The real one is only computed when it is needed.
    private static final ATNConfigSet DEFERRED_START_STATE = new ATNConfigSet(true);

    static {
        DEFERRED_START_STATE.setReadonly(true);
    }

    /**
     * Stops a full context prediction when the limit has been reached.
     * There is no stack trace and the (single) instance is never modified, so it can safely be shared.
     */
    private static final class LookaheadLimitReached extends RuntimeException {
        LookaheadLimitReached() {
            super("The maximum amount of full context lookahead has been reached.", null, false, false);
        }
    }

    private static final LookaheadLimitReached LOOKAHEAD_LIMIT_REACHED = new LookaheadLimitReached();

    private final FullContextPredictionCache cache;
    private final int                        maxFullContextLookahead;

    // The total amount of full context lookahead of this parse
    private int fullContextLookahead = 0;

    // What was reported by the last full context prediction
    private int              reportedStopIndex = -1;
    private boolean          reportedExact = false;
    private @Nullable BitSet reportedAmbiguousAlternatives = null;

    // Statistics of this parse
    private int cacheHits = 0;
    private int cacheMisses = 0;

    /**
     * Create the simulator (with the shared cache and the default limit) for the provided useragent parser.
     * @param parser The parser that will use this simulator.
     */
    UserAgentParserATNSimulator(@NonNull UserAgentParser parser) {
        this(parser, SHARED_CACHE, DEFAULT_MAX_FULL_CONTEXT_LOOKAHEAD);
    }

    /**
     * @param parser                  The parser that will use this simulator.
     * @param cache                   The cache for the outcomes of the full context predictions.
     * @param maxFullContextLookahead The maximum amount of full context lookahead of a single parse.
     */
    UserAgentParserATNSimulator(@NonNull Parser parser,
                                @NonNull FullContextPredictionCache cache,
                                int maxFullContextLookahead) {
        super(parser, parser.getATN(), parser.getInterpreter().decisionToDFA, parser.getInterpreter().getSharedContextCache());
        this.cache = cache;
        this.maxFullContextLookahead = maxFullContextLookahead;
    }

    // The cached outcomes are only valid for the standard LL prediction mode.
    private boolean useCache() {
        return getPredictionMode() == PredictionMode.LL;
    }

    @Override
    @NonNull
    protected ATNConfigSet computeStartState(@NonNull ATNState p, @Nullable RuleContext ctx, boolean fullCtx) {
        if (fullCtx && useCache()) {
            // Only called just before execATNWithFullContext which computes it only if the outcome is not cached.
            return DEFERRED_START_STATE;
        }
        return super.computeStartState(p, ctx, fullCtx);
    }

    @Override
    protected int execATNWithFullContext(@NonNull DFA dfa,
                                         @NonNull DFAState conflictState, // Where the SLL prediction found the conflict
                                         @NonNull ATNConfigSet s0,
                                         @NonNull TokenStream input,
                                         int startIndex,
                                         @Nullable ParserRuleContext outerContext) {
        try {
            if (s0 != DEFERRED_START_STATE) {
                // Not using the cache.
                return super.execATNWithFullContext(dfa, conflictState, s0, input, startIndex, outerContext);
            }

            Outcome outcome = cache.find(dfa.decision, outerContext, input, startIndex);
            if (outcome != null) {
                // Do (and report) exactly the same as the full context prediction would have done.
                cacheHits++;
                if (fullContextLookahead + outcome.getLookahead() > maxFullContextLookahead) {
                    // The real prediction would have reached the limit.
                    fullContextLookahead = maxFullContextLookahead + 1;
                    throw LOOKAHEAD_LIMIT_REACHED;
                }
                fullContextLookahead += outcome.getLookahead();
                int stopIndex = input.index();
                if (outcome.isAmbiguity()) {
                    super.reportAmbiguity(dfa, conflictState, startIndex, stopIndex, outcome.isExact(), outcome.getAmbiguousAlternatives(), null);
                } else {
                    super.reportContextSensitivity(dfa, outcome.getAlternative(), null, startIndex, stopIndex);
                }
                return outcome.getAlternative();
            }

            cacheMisses++;
            int lookaheadBefore = fullContextLookahead;
            reportedStopIndex = -1;
            reportedExact = false;
            reportedAmbiguousAlternatives = null;

            ATNConfigSet startState = super.computeStartState(dfa.atnStartState, outerContext, true);
            int alternative = super.execATNWithFullContext(dfa, conflictState, startState, input, startIndex, outerContext);

            // Only a prediction that reported an ambiguity or a context sensitivity is cached.
            // The other (error) cases are rare and are simply recomputed every time.
            if (reportedStopIndex >= 0) {
                Outcome newOutcome = new Outcome(
                    alternative, fullContextLookahead - lookaheadBefore, reportedExact, reportedAmbiguousAlternatives);
                cache.store(dfa.decision, outerContext, input, startIndex, reportedStopIndex, newOutcome);
                input.seek(reportedStopIndex); // Where the prediction left it
            }
            return alternative;
        } catch (LookaheadLimitReached limitReached) {
            // Predict what the SLL prediction mode predicts (the lowest of the conflicting alternatives)
            // and parse the rest of this useragent using SLL predictions only.
            setPredictionMode(PredictionMode.SLL);
            return conflictState.prediction;
        }
    }

    @Override
    @Nullable
    protected ATNConfigSet computeReachSet(@NonNull ATNConfigSet closure, int t, boolean fullCtx) {
        if (fullCtx) {
            fullContextLookahead++;
            if (fullContextLookahead > maxFullContextLookahead) {
                throw LOOKAHEAD_LIMIT_REACHED;
            }
        }
        return super.computeReachSet(closure, t, fullCtx);
    }

    @Override
    protected void reportContextSensitivity(@NonNull DFA dfa,
                                            int prediction,
                                            @Nullable ATNConfigSet configs,
                                            int startIndex,
                                            int stopIndex) {
        reportedStopIndex = stopIndex;
        reportedExact = false;
        reportedAmbiguousAlternatives = null;
        super.reportContextSensitivity(dfa, prediction, configs, startIndex, stopIndex);
    }

    @Override
    protected void reportAmbiguity(@NonNull DFA dfa,
                                   @Nullable DFAState conflictState,
                                   int startIndex,
                                   int stopIndex,
                                   boolean exact,
                                   @NonNull BitSet ambigAlts,
                                   @Nullable ATNConfigSet configs) {
        reportedStopIndex = stopIndex;
        reportedExact = exact;
        reportedAmbiguousAlternatives = ambigAlts;
        super.reportAmbiguity(dfa, conflictState, startIndex, stopIndex, exact, ambigAlts, configs);
    }

    /**
     * @return The total amount of full context lookahead of this parse.
     */
    int getFullContextLookahead() {
        return fullContextLookahead;
    }

    /**
     * @return The number of full context predictions of this parse that were taken from the cache.
     */
    int getCacheHits() {
        return cacheHits;
    }

    /**
     * @return The number of full context predictions of this parse that had to be computed.
     */
    int getCacheMisses() {
        return cacheMisses;
    }
}
