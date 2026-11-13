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

import nl.basjes.parse.useragent.analyze.WordRangeVisitor.Range;
import nl.basjes.parse.useragent.analyze.treewalker.TreeExpressionEvaluator;
import nl.basjes.parse.useragent.analyze.treewalker.steps.WalkList.WalkResult;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerBaseVisitor;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerLexer;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.IsSyntaxErrorContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherBaseContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherConcatContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherConcatPostfixContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherConcatPrefixContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherDefaultIfNullContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherExtractBrandFromUrlContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherExtractContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherIsValidVersionContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherNormalizeBrandContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherPathIsInLookupContainsContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherPathIsInLookupContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherPathIsInLookupPrefixContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherPathIsNotInLookupContainsContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherPathIsNotInLookupPrefixContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherPathLookupContainsContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherPathLookupPrefixContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherReplaceStringContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherSegmentRangeContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherVariableContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherWordRangeContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.PathVariableContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.StepContainsValueContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.StepNotContainsValueContext;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.StepWordRangeContext;
import nl.basjes.parse.useragent.utils.DefaultANTLRErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import static nl.basjes.parse.useragent.UserAgent.SYNTAX_ERROR;
import static nl.basjes.parse.useragent.analyze.NumberRangeVisitor.NUMBER_RANGE_VISITOR;
import static nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherCleanVersionContext;
import static nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherPathContext;
import static nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherPathIsNullContext;
import static nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.MatcherPathLookupContext;
import static nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.PathFixedValueContext;
import static nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.PathWalkContext;
import static nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.StepDownContext;
import static nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.StepEndsWithValueContext;
import static nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.StepEqualsValueContext;
import static nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.StepNotEqualsValueContext;
import static nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser.StepStartsWithValueContext;
import static nl.basjes.parse.useragent.utils.AntlrUtils.NULL_PARSE_TREE;

/**
 * A single action (variable, require, failIfFound or extract) of a {@link Matcher}.
 * <p>
 * After initialization a MatcherAction is immutable and can be shared by many threads at the same time.
 * All state that changes during the analysis of a single useragent (the matches and the found value)
 * is kept in the {@link ParseState}.
 */
public abstract class MatcherAction implements Serializable {

    private String matchExpression;
    TreeExpressionEvaluator evaluator;

    TreeExpressionEvaluator getEvaluatorForUnitTesting() {
        return evaluator;
    }

    private static final Logger LOG = LogManager.getLogger(MatcherAction.class);

    protected Matcher matcher;
    protected boolean mustHaveMatches = false;

    // The index of this action in the MatchMaker (used to find the state in a ParseState).
    // Transient because it is (re)assigned by the MatchMaker after initialization or deserialization.
    private transient int actionIndex = -1;
    // Copies of the runtime information of the matcher of this action.
    // Retained here so the hot code path does not need to access the (much larger) Matcher object.
    private transient int matcherIndex = -1;
    private transient boolean matcherIsZeroInput = false;

    boolean mustHaveMatches() {
        return mustHaveMatches;
    }

    int getActionIndex() {
        return actionIndex;
    }

    /**
     * Sets the runtime information (done by the Matcher after initialization or deserialization).
     * @param newActionIndex The index of this action in the MatchMaker
     * @param newMatcherIndex The index of the Matcher of this action in the MatchMaker
     * @param newMatcherIsZeroInput Is the Matcher of this action a matcher that does not need any input
     */
    void initializeRuntime(int newActionIndex, int newMatcherIndex, boolean newMatcherIsZeroInput) {
        actionIndex = newActionIndex;
        matcherIndex = newMatcherIndex;
        matcherIsZeroInput = newMatcherIsZeroInput;
    }

    public void destroy() {
        if (evaluator != null) {
            evaluator.destroy();
        }
    }

    // The permanent verbose setting (from the matcher config).
    boolean verbose = false;

    private void setVerbose(boolean newVerbose) {
        setVerbose(newVerbose, false);
    }

    /**
     * Change the verbose setting of this action.
     * A temporary verbose setting is no longer stored in the action (that would not be threadsafe);
     * it is part of the parse instead (see {@link nl.basjes.parse.useragent.UserAgent.MutableUserAgent#setDebug(boolean)}).
     * @param newVerbose The new verbose value
     * @param temporary If true this call is ignored.
     */
    public final void setVerbose(boolean newVerbose, boolean temporary) {
        if (!temporary) {
            this.verbose = newVerbose;
        }
    }

    /**
     * @param state The state of the current parse.
     * @return Must this action output verbose logging in the current parse.
     */
    final boolean isVerbose(@NonNull ParseState state) {
        return verbose || state.isDebug();
    }

    public String getMatchExpression() {
        return matchExpression;
    }

    class InitErrorListener implements DefaultANTLRErrorListener {
        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String msg,
                RecognitionException e) {
            LOG.error("Syntax error");
            LOG.error("Source {}: {}", matcher.getMatcherSourceLocation(), matchExpression);
            LOG.error("Message: {}", msg);
            throw new InvalidParserConfigurationException("Syntax error \"" + msg + "\" caused by \"" + matchExpression + "\".");
        }

        // Ignore all other types of problems
    }

    final void init(String newMatchExpression, Matcher newMatcher) {
        this.matcher = newMatcher;
        this.matchExpression = newMatchExpression;
        setVerbose(newMatcher.getVerbose());
    }

    public long initialize() {
        InitErrorListener errorListener = new InitErrorListener();

        CodePointCharStream input = CharStreams.fromString(this.matchExpression);
        UserAgentTreeWalkerLexer lexer = new UserAgentTreeWalkerLexer(input);

        lexer.addErrorListener(errorListener);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        UserAgentTreeWalkerParser parser = new UserAgentTreeWalkerParser(tokens);

        parser.addErrorListener(errorListener);

        ParserRuleContext requiredPattern = parseWalkerExpression(parser);

        // We couldn't ditch the double quotes around the fixed values in the parsing phase.
        // So we ditch them here. We simply walk the tree and modify some of the tokens.
        new UnQuoteValues().visit(requiredPattern);

        // Now we create an evaluator instance
        evaluator = new TreeExpressionEvaluator(requiredPattern, matcher, verbose);

        // Is a fixed value (i.e. no events will ever be fired)?
        String fixedValue = evaluator.getFixedValue();
        if (fixedValue != null) {
            setFixedValue(fixedValue);
            mustHaveMatches = false;
            return 0; // Not interested in any patterns
        }

        // If this is a failIfFound we do not need any matches from the hashmap.
        if (this instanceof MatcherFailIfFoundAction) {
            mustHaveMatches = false;
        } else {
            mustHaveMatches = evaluator.mustHaveMatches();
        }

        int informs = calculateInformPath(this, "agent", requiredPattern);

        // If this is based on a variable we do not need any matches from the hashmap.
        if (mustHaveMatches && informs == 0) {
            mustHaveMatches = false;
        }

        return informs;
    }

    protected abstract ParserRuleContext parseWalkerExpression(UserAgentTreeWalkerParser parser);

    private static final class UnQuoteValues extends UserAgentTreeWalkerBaseVisitor<Void> {
        private void unQuoteToken(Token token) {
            if (token instanceof CommonToken) {
                CommonToken commonToken = (CommonToken) token;
                commonToken.setStartIndex(commonToken.getStartIndex() + 1);
                commonToken.setStopIndex(commonToken.getStopIndex() - 1);
            }
        }

        @Override
        public Void visitMatcherPathLookup(MatcherPathLookupContext ctx) {
            unQuoteToken(ctx.defaultValue);
            return super.visitMatcherPathLookup(ctx);
        }

        @Override
        public Void visitMatcherPathLookupContains(MatcherPathLookupContainsContext ctx) {
            unQuoteToken(ctx.defaultValue);
            return super.visitMatcherPathLookupContains(ctx);
        }

        @Override
        public Void visitMatcherPathLookupPrefix(MatcherPathLookupPrefixContext ctx) {
            unQuoteToken(ctx.defaultValue);
            return super.visitMatcherPathLookupPrefix(ctx);
        }

        @Override
        public Void visitMatcherDefaultIfNull(MatcherDefaultIfNullContext ctx) {
            unQuoteToken(ctx.defaultValue);
            return super.visitMatcherDefaultIfNull(ctx);
        }

        @Override
        public Void visitPathFixedValue(PathFixedValueContext ctx) {
            unQuoteToken(ctx.value);
            return super.visitPathFixedValue(ctx);
        }

        @Override
        public Void visitMatcherConcat(MatcherConcatContext ctx) {
            unQuoteToken(ctx.prefix);
            unQuoteToken(ctx.postfix);
            return super.visitMatcherConcat(ctx);
        }

        @Override
        public Void visitMatcherConcatPrefix(MatcherConcatPrefixContext ctx) {
            unQuoteToken(ctx.prefix);
            return super.visitMatcherConcatPrefix(ctx);
        }

        @Override
        public Void visitMatcherConcatPostfix(MatcherConcatPostfixContext ctx) {
            unQuoteToken(ctx.postfix);
            return super.visitMatcherConcatPostfix(ctx);
        }

        @Override
        public Void visitMatcherReplaceString(MatcherReplaceStringContext ctx) {
            unQuoteToken(ctx.search);
            unQuoteToken(ctx.replace);
            return super.visitMatcherReplaceString(ctx);
        }

        @Override
        public Void visitStepEqualsValue(StepEqualsValueContext ctx) {
            unQuoteToken(ctx.value);
            return super.visitStepEqualsValue(ctx);
        }

        @Override
        public Void visitStepNotEqualsValue(StepNotEqualsValueContext ctx) {
            unQuoteToken(ctx.value);
            return super.visitStepNotEqualsValue(ctx);
        }

        @Override
        public Void visitStepStartsWithValue(StepStartsWithValueContext ctx) {
            unQuoteToken(ctx.value);
            return super.visitStepStartsWithValue(ctx);
        }

        @Override
        public Void visitStepEndsWithValue(StepEndsWithValueContext ctx) {
            unQuoteToken(ctx.value);
            return super.visitStepEndsWithValue(ctx);
        }

        @Override
        public Void visitStepContainsValue(StepContainsValueContext ctx) {
            unQuoteToken(ctx.value);
            return super.visitStepContainsValue(ctx);
        }

        @Override
        public Void visitStepNotContainsValue(StepNotContainsValueContext ctx) {
            unQuoteToken(ctx.value);
            return super.visitStepNotContainsValue(ctx);
        }
    }

    protected abstract void setFixedValue(String newFixedValue);

    /**
     * For each key that this action wants to be notified for this method is called.
     * Note that on a single parse event the same name CAN be called multiple times!!
     *
     * @param state  The state of the current parse
     * @param key    The key of the node
     * @param value  The value that was found
     * @param result The node in the parser tree where the match occurred
     */
    void inform(@NonNull ParseState state, @Nullable String key, @Nullable String value, @Nullable ParseTree result) {
        // The matcher received input. The first time we record that the matcher needs to be evaluated.
        if (state.markReceivedInput(matcherIndex) && !matcherIsZeroInput) {
            state.addTouchedMatcher(matcher);
        }

        boolean isFirstMatch = state.addMatch(this, key, value, result);
        // Only if this needs input we tell the matcher on the first one.
        if (mustHaveMatches && isFirstMatch) {
            state.incrementReceivedInput(matcherIndex);
        }
    }

    protected abstract void inform(@NonNull ParseState state, @Nullable String key, @NonNull WalkResult foundValue);

    /**
     * @param state The state of the current parse
     * @return If it is impossible that this can be valid it returns true, else false.
     */
    boolean cannotBeValid(@NonNull ParseState state) {
        if (mustHaveMatches) {
            return !state.hasMatches(this);
        }
        return false;
    }

    /**
     * Called after all nodes have been notified.
     * @param state The state of the current parse
     * @return true if the obtainResult result was valid. False will fail the entire matcher this belongs to.
     */
    abstract boolean obtainResult(@NonNull ParseState state);

    boolean isValidWithoutMatches(@NonNull ParseState state) {
        return !state.hasMatches(this) && !evaluator.mustHaveMatches();
    }

    /**
     * Optimization: Only if there is a possibility that all actions for this matcher CAN be valid do we
     * actually perform the analysis and do the (expensive) tree walking and matching.
     * @param state The state of the current parse
     */
    void processInformedMatches(@NonNull ParseState state) {
        for (int match = state.firstMatch(this); match != -1; match = state.nextMatch(match)) {
            String     matchKey     = state.getMatchKey(match);
            WalkResult matchedValue = evaluator.evaluate(state.getMatchTree(match), matchKey, state.getMatchValue(match));
            if (matchedValue != null) {
                inform(state, matchKey, matchedValue);
                return; // We always stick to the first match
            }
        }

        if (isValidWithoutMatches(state)) {
            WalkResult matchedValue = evaluator.evaluate(NULL_PARSE_TREE, null, null);
            if (matchedValue != null) {
                inform(state, null, matchedValue);
            }
        }
    }

    // ============================================================================================================

    @FunctionalInterface
    private interface CalculateInformPathFunction {
        /**
         * Applies this function to the given arguments.
         * @param action The applicable action.
         * @param treeName The name of the current tree.
         * @param tree The actual location in the parseTree
         * @return the number of informs done
         */
        int calculateInformPath(MatcherAction action, String treeName, ParserRuleContext tree);
    }

    private static final Map<Class<?>, CalculateInformPathFunction> CALCULATE_INFORM_PATH = new HashMap<>();

    static {
        // -------------
        CALCULATE_INFORM_PATH.put(MatcherBaseContext.class,             (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherBaseContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherPathIsNullContext.class,       (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherPathIsNullContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(IsSyntaxErrorContext.class,           (action, treeName, tree) -> {
            action.matcher.informMeAbout(action, SYNTAX_ERROR + "=" + ((IsSyntaxErrorContext) tree).value.getText());
            return 1;
        });

        // -------------
        CALCULATE_INFORM_PATH.put(MatcherExtractContext.class,          (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherExtractContext) tree).expression));

        // -------------
        CALCULATE_INFORM_PATH.put(MatcherVariableContext.class,         (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherVariableContext) tree).expression));

        // -------------
        CALCULATE_INFORM_PATH.put(MatcherPathContext.class,             (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherPathContext) tree).basePath()));
        CALCULATE_INFORM_PATH.put(MatcherConcatContext.class,           (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherConcatContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherConcatPrefixContext.class,     (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherConcatPrefixContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherConcatPostfixContext.class,    (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherConcatPostfixContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherNormalizeBrandContext.class,   (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherNormalizeBrandContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherExtractBrandFromUrlContext.class,   (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherExtractBrandFromUrlContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherCleanVersionContext.class,     (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherCleanVersionContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherIsValidVersionContext.class,     (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherIsValidVersionContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherReplaceStringContext.class,     (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherReplaceStringContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherPathLookupContext.class,       (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherPathLookupContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherPathIsInLookupContext.class,  (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherPathIsInLookupContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherPathLookupContainsContext.class,      (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherPathLookupContainsContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherPathIsInLookupContainsContext.class,  (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherPathIsInLookupContainsContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherPathIsNotInLookupContainsContext.class,  (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherPathIsNotInLookupContainsContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherPathLookupPrefixContext.class,        (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherPathLookupPrefixContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherPathIsInLookupPrefixContext.class,    (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherPathIsInLookupPrefixContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherPathIsNotInLookupPrefixContext.class,    (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherPathIsNotInLookupPrefixContext) tree).matcher()));
        CALCULATE_INFORM_PATH.put(MatcherDefaultIfNullContext.class,           (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherDefaultIfNullContext) tree).matcher()));
        CALCULATE_INFORM_PATH .put(MatcherWordRangeContext.class,        (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherWordRangeContext) tree).matcher()));
        CALCULATE_INFORM_PATH .put(MatcherSegmentRangeContext.class,        (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((MatcherSegmentRangeContext) tree).matcher()));

        // -------------
        CALCULATE_INFORM_PATH.put(PathVariableContext.class,            (action, treeName, tree) -> {
            action.matcher.informMeAboutVariable(action, ((PathVariableContext) tree).variable.getText());
            return 0;
        });

        CALCULATE_INFORM_PATH.put(PathWalkContext.class,                (action, treeName, tree) ->
            calculateInformPath(action, treeName, ((PathWalkContext) tree).nextStep));

        // -------------
        CALCULATE_INFORM_PATH.put(StepDownContext.class,                (action, treeName, tree) -> {
            StepDownContext thisTree = ((StepDownContext)tree);
            int informs = 0;
            for (int number : NUMBER_RANGE_VISITOR.visit(thisTree.numberRange())) {
                informs += calculateInformPath(action, treeName + '.' + "(" + number + ")" + thisTree.name.getText(), thisTree.nextStep);
            }
            return informs;
        });

        CALCULATE_INFORM_PATH.put(StepEqualsValueContext.class,         (action, treeName, tree) -> {
            StepEqualsValueContext thisTree = ((StepEqualsValueContext)tree);
            action.matcher.informMeAbout(action, treeName + "=\"" + thisTree.value.getText() + "\"");
            return 1;
        });

        CALCULATE_INFORM_PATH.put(StepStartsWithValueContext.class,     (action, treeName, tree) -> {
            StepStartsWithValueContext thisTree = ((StepStartsWithValueContext)tree);
            action.matcher.informMeAboutPrefix(action, treeName, thisTree.value.getText());
            return 1;
        });

        CALCULATE_INFORM_PATH.put(StepWordRangeContext.class,           (action, treeName, tree) -> {
            StepWordRangeContext thisTree = ((StepWordRangeContext)tree);
            Range range = WordRangeVisitor.getRange(thisTree.wordRange());
            action.matcher.lookingForRange(treeName, range);
            return calculateInformPath(action, treeName + range, thisTree.nextStep);
        });
    }

    private static int calculateInformPath(MatcherAction action, String treeName, ParserRuleContext tree) {
        if (tree == null) {
            action.matcher.informMeAbout(action, treeName);
            return 1;
        }

        CalculateInformPathFunction function = CALCULATE_INFORM_PATH.get(tree.getClass());
        if (function != null) {
            return function.calculateInformPath(action, treeName, tree);
        }

        action.matcher.informMeAbout(action, treeName);
        return 1;
    }

    // ============================================================================================================

    /**
     * There is no state to reset anymore (it is all in the ParseState).
     */
    public void reset() {
        // Nothing to do: All state is held in the ParseState of each parse.
    }

    /**
     * Debugging only: The matches found for this action during the most recent parse.
     * @return A copy of the list of matches.
     */
    public MatchesList getMatches() {
        return getMatches(matcher == null ? null : matcher.getDebugParseState());
    }

    @NonNull
    MatchesList getMatches(@Nullable ParseState state) {
        if (state == null) {
            return new MatchesList(0);
        }
        return state.getMatches(this);
    }

    @Override
    public String toString() {
        return "MatcherAction{" +
            "matchExpression='" + matchExpression + '\'' +
            ", evaluator=" + evaluator +
//            ", matcher=" + matcher +
            ", mustHaveMatches=" + mustHaveMatches +
            ", verbose=" + verbose +
            '}';
    }
}
