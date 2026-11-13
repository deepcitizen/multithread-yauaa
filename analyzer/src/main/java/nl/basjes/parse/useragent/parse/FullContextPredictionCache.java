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

import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * A threadsafe, lock free and bounded cache of the outcomes of the full context (LL) predictions of a grammar.
 * <p>
 * The outcome of a full context prediction is completely determined by
 * <ul>
 *     <li>the decision,</li>
 *     <li>the invoking states of all the rule contexts on the stack (the "full context") and</li>
 *     <li>the types of the tokens the prediction has looked at.</li>
 * </ul>
 * This is only true for a grammar without semantic predicates, without left recursive rules (which would
 * introduce precedence predicates) and with a start rule that is not used by any other rule (within a single
 * prediction ANTLR uses one merge cache for both the SLL and the full context phase which ignores the difference
 * in how they handle the root context). The useragent grammar meets all of these conditions.
 * <p>
 * So the outcome is stored in a trie with the path [decision] [invoking states] [token types] and can be replayed
 * exactly when the same path is found again. Because the token types are used (not the texts) a single entry
 * is used for all useragents that only differ in things like the version numbers.
 * <p>
 * Reading is lock free; adding a node uses a compare-and-set of an immutable (copy-on-write) array of children.
 * The trie is bounded: when it gets too big it is discarded and rebuilt from scratch.
 * Neither of these affect the results, only the performance.
 */
final class FullContextPredictionCache {

    /** The default maximum number of nodes in the trie (on average a node uses less than 100 bytes). */
    static final int DEFAULT_MAX_NODES = 50_000;

    /**
     * The default maximum length (invoking states + tokens) of a path that is stored.
     * Normal useragents need far less; this avoids filling the trie with the very long lookaheads of
     * malformed or malicious useragents.
     */
    static final int DEFAULT_MAX_PATH_LENGTH = 256;

    /**
     * The outcome of a single full context prediction: the predicted alternative and what was reported.
     * Instances are immutable.
     */
    static final class Outcome {
        private final int              alternative;
        private final int              lookahead;
        private final boolean          exact;
        private final @Nullable BitSet ambiguousAlternatives;

        /**
         * @param alternative           The predicted alternative.
         * @param lookahead             The amount of full context lookahead that was needed for this prediction.
         * @param exact                 The value of the 'exact' flag of the reported ambiguity.
         * @param ambiguousAlternatives The reported ambiguous alternatives. Must be null if a context sensitivity
         *                              (instead of an ambiguity) was reported. A copy is retained.
         */
        Outcome(int alternative, int lookahead, boolean exact, @Nullable BitSet ambiguousAlternatives) {
            this.alternative           = alternative;
            this.lookahead             = lookahead;
            this.exact                 = exact;
            this.ambiguousAlternatives = ambiguousAlternatives == null ? null : (BitSet) ambiguousAlternatives.clone();
        }

        int getAlternative() {
            return alternative;
        }

        /**
         * @return The amount of full context lookahead that was needed to make this prediction.
         */
        int getLookahead() {
            return lookahead;
        }

        boolean isExact() {
            return exact;
        }

        /**
         * @return true if the prediction reported an ambiguity, false if it reported a context sensitivity.
         */
        boolean isAmbiguity() {
            return ambiguousAlternatives != null;
        }

        /**
         * @return A copy of the ambiguous alternatives that were reported, null if this is not an ambiguity.
         */
        @Nullable
        BitSet getAmbiguousAlternatives() {
            return ambiguousAlternatives == null ? null : (BitSet) ambiguousAlternatives.clone();
        }
    }

    private static final class Node {
        private static final Node[] NO_CHILDREN = new Node[0];

        private static final AtomicReferenceFieldUpdater<Node, Node[]> CHILDREN =
            AtomicReferenceFieldUpdater.newUpdater(Node.class, Node[].class, "children");

        private final int symbol;

        // The children sorted by their symbol. The array is never modified: it is replaced by a new one.
        private volatile Node[] children = NO_CHILDREN;

        // The outcome of the prediction that stopped at this node (null if no prediction stopped here).
        private volatile @Nullable Outcome outcome = null;

        Node(int symbol) {
            this.symbol = symbol;
        }

        @Nullable
        Node getChild(int childSymbol) {
            Node[] current = children;
            int index = indexOf(current, childSymbol);
            return index < 0 ? null : current[index];
        }

        /**
         * @param childSymbol The symbol of the child.
         * @param size The counter of the number of nodes in the trie.
         * @return The child with the provided symbol (created if it did not exist yet).
         */
        @NonNull
        Node getOrCreateChild(int childSymbol, @NonNull AtomicInteger size) {
            while (true) {
                Node[] current = children;
                int index = indexOf(current, childSymbol);
                if (index >= 0) {
                    return current[index];
                }
                int insertAt = -(index + 1);
                Node child = new Node(childSymbol);
                Node[] updated = new Node[current.length + 1];
                System.arraycopy(current, 0, updated, 0, insertAt);
                updated[insertAt] = child;
                System.arraycopy(current, insertAt, updated, insertAt + 1, current.length - insertAt);
                if (CHILDREN.compareAndSet(this, current, updated)) {
                    size.incrementAndGet();
                    return child;
                }
                // Another thread has changed the children at the same time: simply try again.
            }
        }

        /**
         * Binary search on the symbols of the (sorted) nodes.
         * @return The index of the node with the symbol, or (-(insertion point) - 1) if there is no such node.
         */
        private static int indexOf(@NonNull Node[] nodes, int symbol) {
            int low = 0;
            int high = nodes.length - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int middleSymbol = nodes[middle].symbol;
                if (middleSymbol < symbol) {
                    low = middle + 1;
                } else if (middleSymbol > symbol) {
                    high = middle - 1;
                } else {
                    return middle;
                }
            }
            return -(low + 1);
        }
    }

    private static final class Trie {
        private final Node[]        decisions;
        private final AtomicInteger size = new AtomicInteger();

        Trie(int numberOfDecisions) {
            decisions = new Node[numberOfDecisions];
            for (int decision = 0; decision < numberOfDecisions; decision++) {
                decisions[decision] = new Node(decision);
            }
        }
    }

    private final int                   numberOfDecisions;
    private final int                   maxNodes;
    private final int                   maxPathLength;
    private final AtomicReference<Trie> trie;
    private final AtomicInteger         resets = new AtomicInteger();

    /**
     * @param numberOfDecisions The number of decisions in the ATN of the grammar.
     * @param maxNodes          When the trie has more than this number of nodes it is discarded.
     * @param maxPathLength     Outcomes with a longer path (invoking states + tokens) are not stored.
     */
    FullContextPredictionCache(int numberOfDecisions, int maxNodes, int maxPathLength) {
        this.numberOfDecisions = numberOfDecisions;
        this.maxNodes          = maxNodes;
        this.maxPathLength     = maxPathLength;
        this.trie              = new AtomicReference<>(new Trie(numberOfDecisions));
    }

    // The invoking states (which are >= 0) are mapped to symbols that are below all token types (which are >= EOF).
    private static int invokingStateSymbol(int invokingState) {
        return Token.EOF - 1 - invokingState;
    }

    /**
     * Find the stored outcome of a full context prediction.
     * If it is found the input is positioned on the token where the prediction stopped.
     * If it is not found the position of the input is undefined.
     *
     * @param decision     The decision.
     * @param outerContext The rule context in which the prediction is done.
     * @param input        The tokens.
     * @param startIndex   The index of the first token of the prediction.
     * @return The outcome or null if it is not (yet) known.
     */
    @Nullable
    Outcome find(int decision, @Nullable RuleContext outerContext, @NonNull TokenStream input, int startIndex) {
        if (decision < 0 || decision >= numberOfDecisions) {
            return null;
        }
        Node node = trie.get().decisions[decision];

        // The full context: the invoking states of all the rule contexts on the stack
        for (RuleContext context = outerContext; context != null && context.parent != null; context = context.parent) {
            if (context.invokingState < 0) {
                return null; // Never stored
            }
            node = node.getChild(invokingStateSymbol(context.invokingState));
            if (node == null) {
                return null;
            }
        }

        // The tokens
        input.seek(startIndex);
        while (true) {
            int tokenType = input.LA(1);
            node = node.getChild(tokenType);
            if (node == null) {
                return null;
            }
            Outcome outcome = node.outcome;
            if (outcome != null) {
                return outcome;
            }
            if (tokenType == Token.EOF) {
                return null;
            }
            input.consume();
        }
    }

    /**
     * Store the outcome of a full context prediction.
     * The input is used to determine the token types (it is moved).
     *
     * @param decision     The decision.
     * @param outerContext The rule context in which the prediction was done.
     * @param input        The tokens.
     * @param startIndex   The index of the first token of the prediction.
     * @param stopIndex    The index of the token where the prediction stopped (as reported by the prediction).
     * @param outcome      The outcome of the prediction.
     */
    void store(int decision,
               @Nullable RuleContext outerContext,
               @NonNull TokenStream input,
               int startIndex,
               int stopIndex,
               @NonNull Outcome outcome) {
        if (decision < 0 || decision >= numberOfDecisions || stopIndex < startIndex) {
            return;
        }

        int pathLength = stopIndex - startIndex + 1; // An upper bound for the number of tokens
        for (RuleContext context = outerContext; context != null && context.parent != null; context = context.parent) {
            if (context.invokingState < 0) {
                return; // Cannot be stored
            }
            pathLength++;
        }
        if (pathLength > maxPathLength) {
            return;
        }

        Trie current = trie.get();
        Node node = current.decisions[decision];
        for (RuleContext context = outerContext; context != null && context.parent != null; context = context.parent) {
            node = node.getOrCreateChild(invokingStateSymbol(context.invokingState), current.size);
        }

        input.seek(startIndex);
        while (true) {
            int tokenType = input.LA(1);
            node = node.getOrCreateChild(tokenType, current.size);
            if (input.index() >= stopIndex || tokenType == Token.EOF) {
                node.outcome = outcome;
                break;
            }
            input.consume();
        }

        if (current.size.get() > maxNodes && trie.compareAndSet(current, new Trie(numberOfDecisions))) {
            resets.incrementAndGet();
        }
    }

    /**
     * @return The current number of nodes in the trie.
     */
    int size() {
        return trie.get().size.get();
    }

    /**
     * @return How often the trie was discarded because it became too big.
     */
    int getResets() {
        return resets.get();
    }

    /**
     * Discard all stored outcomes.
     */
    void clear() {
        trie.set(new Trie(numberOfDecisions));
    }
}
