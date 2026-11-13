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

package nl.basjes.parse.useragent.analyze;

import nl.basjes.parse.useragent.parse.UserAgentTreeFlattener.PathInformer;
import org.antlr.v4.runtime.tree.ParseTree;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

import static nl.basjes.parse.useragent.UserAgent.NULL_VALUE;

/**
 * Holds ALL mutable state that is needed while parsing and analyzing a single useragent.
 * <p>
 * All rules (the {@link Matcher}s, {@link MatcherAction}s, lookups and the big hashmaps) are shared between threads
 * and are not modified after initialization. Everything that changes during a single analysis lives in an instance
 * of this class. An instance is used by only one thread at a time (it is taken from a pool in the
 * {@link UserAgentStringMatchMaker}) and it is reused afterwards to avoid creating garbage.
 * <p>
 * Starting a new parse is O(1): every per-matcher and per-action slot remembers the generation in which it was
 * last written and a slot from an older generation is treated as empty.
 * <p>
 * This class is NOT threadsafe by design.
 */
public final class ParseState implements PathInformer {

    private static final int INITIAL_MATCHES_CAPACITY = 256;
    private static final int INITIAL_TOUCHED_CAPACITY = 64;
    private static final int INITIAL_RESULTS_CAPACITY = 16;

    // The state of a single matcher is kept together (memory locality): [ generation, receivedInput ]
    private static final int MATCHER_GENERATION     = 0;
    private static final int MATCHER_RECEIVED_INPUT = 1;
    private static final int MATCHER_STATE_SIZE     = 2;

    // The state of a single action is kept together (memory locality): 16 bytes so 4 actions per cache line.
    private static final int ACTION_GENERATION       = 0; // The generation of the matches
    private static final int ACTION_FIRST_MATCH      = 1; // -1 == none
    private static final int ACTION_LAST_MATCH       = 2; // -1 == none
    private static final int ACTION_FOUND_GENERATION = 3; // The generation of the found value
    private static final int ACTION_STATE_SIZE       = 4;

    private final UserAgentStringMatchMaker matchMaker;

    // The generation (i.e. parse run) for which the slots below are valid. 0 means 'never written'.
    private int generation = 0;

    private boolean debug = false;   // The useragent that is being parsed requested debug output.
    private boolean verbose = false; // The analyzer was set to verbose when this parse started.

    // ---------------------------------------------------------------
    // Per Matcher state, indexed by Matcher.getMatcherIndex() * MATCHER_STATE_SIZE
    private final long[] matcherState;

    // The matchers (excluding the zero-input ones) that received input in this parse in the order of first input.
    private Matcher[] touchedMatchers = new Matcher[INITIAL_TOUCHED_CAPACITY];
    private int       touchedMatchersSize = 0;

    // ---------------------------------------------------------------
    // Per MatcherAction state, indexed by MatcherAction.getActionIndex() * ACTION_STATE_SIZE
    private final int[]               actionState;
    // The found value of each action, indexed by MatcherAction.getActionIndex() (only valid in ACTION_FOUND_GENERATION).
    private final @Nullable Object[]  actionFoundValue;

    // ---------------------------------------------------------------
    // All matches of all actions are stored in these arrays.
    // The matches of a single action form a linked list (in order of arrival) via matchNext.
    private @Nullable String[]    matchKeys   = new String[INITIAL_MATCHES_CAPACITY];
    private @Nullable String[]    matchValues = new String[INITIAL_MATCHES_CAPACITY];
    private @Nullable ParseTree[] matchTrees  = new ParseTree[INITIAL_MATCHES_CAPACITY];
    private int[]                 matchNext   = new int[INITIAL_MATCHES_CAPACITY];
    private int                   matchesSize = 0;

    // ---------------------------------------------------------------
    // The values extracted by the Matcher that is currently being analyzed.
    private @Nullable String[] resultValues      = new String[INITIAL_RESULTS_CAPACITY];
    private long[]             resultConfidences = new long[INITIAL_RESULTS_CAPACITY];

    ParseState(@NonNull UserAgentStringMatchMaker matchMaker, int numberOfMatchers, int numberOfActions) {
        this.matchMaker       = matchMaker;
        this.matcherState     = new long[numberOfMatchers * MATCHER_STATE_SIZE];
        this.actionState      = new int[numberOfActions * ACTION_STATE_SIZE];
        this.actionFoundValue = new Object[numberOfActions];
    }

    /**
     * Prepare this instance for a new parse. All state of a previous parse is (logically) discarded.
     * @param newDebug   Must the actions output debug logging
     * @param newVerbose Must the analyzer output verbose logging
     */
    void startParse(boolean newDebug, boolean newVerbose) {
        if (generation == Integer.MAX_VALUE) {
            // Extremely rare: Wrap around of the generation counter.
            // Wipe everything so no slot can accidentally look valid in a later generation.
            Arrays.fill(matcherState, 0);
            Arrays.fill(actionState, 0);
            Arrays.fill(actionFoundValue, null);
            generation = 0;
        }
        generation++;

        // NOTE: The old values are not wiped (that costs time): They are simply overwritten.
        //       So an instance retains (at most) the data of a single previous parse (which is also used for debugging).
        touchedMatchersSize = 0;
        matchesSize = 0;

        this.debug = newDebug;
        this.verbose = newVerbose;
    }

    /**
     * @return Did the useragent that is being parsed request debug output.
     */
    boolean isDebug() {
        return debug;
    }

    /**
     * @return Is the analyzer verbose for this parse.
     */
    boolean isVerbose() {
        return verbose;
    }

    // ============================================================================================================
    // This is the callback used by the UserAgentTreeFlattener for every node in the parse tree.

    @Override
    public void inform(@NonNull String path, @NonNull String value, @Nullable ParseTree ctx) {
        matchMaker.inform(this, path, value, ctx);
    }

    /**
     * Only for testing the wrap around of the generation counter.
     * @param newGeneration The new value of the generation counter.
     */
    void setGenerationForTesting(int newGeneration) {
        generation = newGeneration;
    }

    // ============================================================================================================
    // Matcher state

    private int validMatcherSlot(int matcherIndex) {
        int base = matcherIndex * MATCHER_STATE_SIZE;
        if (matcherState[base + MATCHER_GENERATION] != generation) {
            matcherState[base + MATCHER_GENERATION]     = generation;
            matcherState[base + MATCHER_RECEIVED_INPUT] = 0;
        }
        return base;
    }

    /**
     * Records that the matcher has received input.
     * @param matcherIndex The index of the matcher that received input.
     * @return true if this is the first input in this parse for this matcher.
     */
    boolean markReceivedInput(int matcherIndex) {
        int base = matcherIndex * MATCHER_STATE_SIZE;
        if (matcherState[base + MATCHER_GENERATION] == generation) {
            return false;
        }
        matcherState[base + MATCHER_GENERATION]     = generation;
        matcherState[base + MATCHER_RECEIVED_INPUT] = 0;
        return true;
    }

    boolean hasReceivedInput(@NonNull Matcher matcher) {
        return matcherState[matcher.getMatcherIndex() * MATCHER_STATE_SIZE + MATCHER_GENERATION] == generation;
    }

    void addTouchedMatcher(@NonNull Matcher matcher) {
        if (touchedMatchersSize == touchedMatchers.length) {
            touchedMatchers = Arrays.copyOf(touchedMatchers, touchedMatchers.length * 2);
        }
        touchedMatchers[touchedMatchersSize++] = matcher;
    }

    int getTouchedMatchersSize() {
        return touchedMatchersSize;
    }

    @Nullable
    Matcher getTouchedMatcher(int index) {
        return touchedMatchers[index];
    }

    @NonNull
    MatcherList getTouchedMatchers() {
        MatcherList result = new MatcherList(Math.max(1, touchedMatchersSize));
        for (int i = 0; i < touchedMatchersSize; i++) {
            result.add(touchedMatchers[i]);
        }
        return result;
    }

    long getReceivedInput(@NonNull Matcher matcher) {
        int base = matcher.getMatcherIndex() * MATCHER_STATE_SIZE;
        if (matcherState[base + MATCHER_GENERATION] != generation) {
            return 0;
        }
        return matcherState[base + MATCHER_RECEIVED_INPUT];
    }

    void incrementReceivedInput(int matcherIndex) {
        matcherState[validMatcherSlot(matcherIndex) + MATCHER_RECEIVED_INPUT]++;
    }

    void failImmediately(@NonNull Matcher matcher) {
        // So it will never match the expected
        matcherState[validMatcherSlot(matcher.getMatcherIndex()) + MATCHER_RECEIVED_INPUT] = Long.MIN_VALUE;
    }

    // ============================================================================================================
    // MatcherAction state

    private int validActionSlot(@NonNull MatcherAction action) {
        int base = action.getActionIndex() * ACTION_STATE_SIZE;
        if (actionState[base + ACTION_GENERATION] != generation) {
            actionState[base + ACTION_GENERATION]  = generation;
            actionState[base + ACTION_FIRST_MATCH] = -1;
            actionState[base + ACTION_LAST_MATCH]  = -1;
        }
        return base;
    }

    /**
     * Add a match to the provided action.
     * @param action The action that received the match
     * @param key The key (path) of the match
     * @param value The value of the match
     * @param tree The node in the parse tree of the match
     * @return true if this is the first match of this action in this parse.
     */
    boolean addMatch(@NonNull MatcherAction action, @Nullable String key, @Nullable String value, @Nullable ParseTree tree) {
        int base = validActionSlot(action);

        if (matchesSize == matchKeys.length) {
            // First allocate everything so the state is never inconsistent (even in case of an OutOfMemoryError)
            int newCapacity = matchKeys.length * 2;
            String[]    newMatchKeys   = Arrays.copyOf(matchKeys,   newCapacity);
            String[]    newMatchValues = Arrays.copyOf(matchValues, newCapacity);
            ParseTree[] newMatchTrees  = Arrays.copyOf(matchTrees,  newCapacity);
            int[]       newMatchNext   = Arrays.copyOf(matchNext,   newCapacity);
            matchKeys   = newMatchKeys;
            matchValues = newMatchValues;
            matchTrees  = newMatchTrees;
            matchNext   = newMatchNext;
        }
        int match = matchesSize++;
        matchKeys[match]   = key;
        matchValues[match] = value;
        matchTrees[match]  = tree;
        matchNext[match]   = -1;

        int last = actionState[base + ACTION_LAST_MATCH];
        actionState[base + ACTION_LAST_MATCH] = match;
        if (last == -1) {
            actionState[base + ACTION_FIRST_MATCH] = match;
            return true;
        }
        matchNext[last] = match;
        return false;
    }

    boolean hasMatches(@NonNull MatcherAction action) {
        int base = action.getActionIndex() * ACTION_STATE_SIZE;
        return actionState[base + ACTION_GENERATION] == generation && actionState[base + ACTION_FIRST_MATCH] != -1;
    }

    int getMatchCount(@NonNull MatcherAction action) {
        int count = 0;
        for (int match = firstMatch(action); match != -1; match = nextMatch(match)) {
            count++;
        }
        return count;
    }

    /**
     * @param action The action for which we want the matches.
     * @return The handle of the first match of this action or -1 if there are none.
     */
    int firstMatch(@NonNull MatcherAction action) {
        int base = action.getActionIndex() * ACTION_STATE_SIZE;
        if (actionState[base + ACTION_GENERATION] != generation) {
            return -1;
        }
        return actionState[base + ACTION_FIRST_MATCH];
    }

    /**
     * @param match The handle of the current match
     * @return The handle of the next match of the same action or -1 if there are no more.
     */
    int nextMatch(int match) {
        return matchNext[match];
    }

    @Nullable
    String getMatchKey(int match) {
        return matchKeys[match];
    }

    @Nullable
    String getMatchValue(int match) {
        return matchValues[match];
    }

    @Nullable
    ParseTree getMatchTree(int match) {
        return matchTrees[match];
    }

    /**
     * Creates a copy of the matches of an action (only used for debugging and testing).
     * @param action The action for which we want the matches.
     * @return A new MatchesList with all the matches.
     */
    @NonNull
    MatchesList getMatches(@NonNull MatcherAction action) {
        MatchesList result = new MatchesList(getMatchCount(action));
        for (int match = firstMatch(action); match != -1; match = nextMatch(match)) {
            result.add(matchKeys[match], matchValues[match], matchTrees[match]);
        }
        return result;
    }

    @Nullable
    Object getFoundValue(@NonNull MatcherAction action) {
        int index = action.getActionIndex();
        if (actionState[index * ACTION_STATE_SIZE + ACTION_FOUND_GENERATION] != generation) {
            return null;
        }
        return actionFoundValue[index];
    }

    void setFoundValue(@NonNull MatcherAction action, @Nullable Object value) {
        int index = action.getActionIndex();
        actionState[index * ACTION_STATE_SIZE + ACTION_FOUND_GENERATION] = generation;
        actionFoundValue[index] = value;
    }

    // ============================================================================================================
    // The results of the matcher that is being analyzed

    void startMatcherResults(@Nullable String @NonNull [] templateValues, long @NonNull [] templateConfidences) {
        int size = templateValues.length;
        if (resultValues.length < size) {
            // First allocate everything so the state is never inconsistent (even in case of an OutOfMemoryError)
            String[] newResultValues      = new String[size];
            long[]   newResultConfidences = new long[size];
            resultValues      = newResultValues;
            resultConfidences = newResultConfidences;
        }
        System.arraycopy(templateValues,      0, resultValues,      0, size);
        System.arraycopy(templateConfidences, 0, resultConfidences, 0, size);
    }

    void setMatcherResult(int slot, @Nullable String value, long confidence) {
        // Same as what MutableAgentField::setValueForced does.
        resultValues[slot]      = NULL_VALUE.equals(value) ? null : value;
        resultConfidences[slot] = confidence;
    }

    @Nullable String @NonNull [] getResultValues() {
        return resultValues;
    }

    long @NonNull [] getResultConfidences() {
        return resultConfidences;
    }

    @Override
    public String toString() {
        return "ParseState{" +
            "generation=" + generation +
            ", touchedMatchers=" + touchedMatchersSize +
            ", matches=" + matchesSize +
            '}';
    }
}
