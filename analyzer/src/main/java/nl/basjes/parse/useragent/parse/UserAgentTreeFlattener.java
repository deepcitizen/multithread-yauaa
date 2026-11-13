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

import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgent.MutableUserAgent;
import nl.basjes.parse.useragent.analyze.MatchMaker;
import nl.basjes.parse.useragent.analyze.WordRangeVisitor.Range;
import nl.basjes.parse.useragent.parser.UserAgentBaseListener;
import nl.basjes.parse.useragent.parser.UserAgentLexer;
import nl.basjes.parse.useragent.parser.UserAgentParser;
import nl.basjes.parse.useragent.parser.UserAgentParser.Base64Context;
import nl.basjes.parse.useragent.parser.UserAgentParser.CommentBlockContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.CommentEntryContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.CommentProductContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.EmailAddressContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.EmptyWordContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.KeyNameContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.KeyValueContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.KeyValueProductVersionNameContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.KeyValueVersionNameContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.MultipleWordsContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.ProductContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.ProductNameEmailContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.ProductNameKeyValueContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.ProductNameUrlContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.ProductNameUuidContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.ProductNameVersionContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.ProductNameWordsContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.ProductVersionContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.ProductVersionWithCommasContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.ProductVersionWordsContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.RootTextContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.SingleVersionContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.SingleVersionWithCommasContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.SiteUrlContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.UserAgentContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.UuIdContext;
import nl.basjes.parse.useragent.parser.UserAgentParser.VersionWordsContext;
import nl.basjes.parse.useragent.utils.Splitter;
import nl.basjes.parse.useragent.utils.VersionSplitter;
import nl.basjes.parse.useragent.utils.WordSplitter;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.commons.lang3.tuple.Pair;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

import static nl.basjes.parse.useragent.UserAgent.SYNTAX_ERROR;
import static nl.basjes.parse.useragent.utils.AntlrUtils.getSourceText;

/**
 * Parses a useragent and informs the receiver about every node (path and value) in the resulting parse tree.
 * <p>
 * This class is threadsafe: All state of a single parse is held in a {@link FlattenerWalker} that is created
 * for every parse.
 */
public class UserAgentTreeFlattener implements Serializable {

    /**
     * The receiver of all the nodes (path + value) found in the parse tree.
     */
    @FunctionalInterface
    public interface PathInformer {
        /**
         * Called for every node (and every needed subrange of the value of the node) in the parse tree.
         * @param path The path of the node
         * @param value The value of the node
         * @param ctx The node in the parse tree
         */
        void inform(@NonNull String path, @NonNull String value, @Nullable ParseTree ctx);
    }

    /**
     * The maximum nesting depth of the brackets ('(', '[' and '{') of a useragent that is parsed.
     * A useragent with deeper nested brackets is not parsed (a {@link UserAgentTooDeeplyNestedException} is thrown).
     * <p>
     * The parser needs a lot of stack for every level: with the default thread stack (1 MiB) a nesting of about
     * 150 levels caused a StackOverflowError. At this limit even a thread stack of 256 KiB is enough.
     * Normal useragents have at most 2 or 3 levels.
     */
    public static final int MAX_NESTING_DEPTH = 20;

    private final MatchMaker analyzer;

    private static final String AGENT    = "agent";
    private static final String PRODUCT  = "product";
    private static final String NAME     = "name";
    private static final String VERSION  = "version";
    private static final String COMMENTS = "comments";
    private static final String KEYVALUE = "keyvalue";
    private static final String KEY      = "key";
    private static final String TEXT     = "text";
    private static final String URL      = "url";
    private static final String UUID     = "uuid";
    private static final String EMAIL    = "email";
    private static final String BASE64   = "base64";

    enum PathType {
        CHILD,
        COMMENT,
        VERSION
    }

    /**
     * The path information of a single node in the parse tree.
     */
    public static final class State {
        long child = 0;
        long version = 0;
        long comment = 0;
        final String name;
        String path;
        ParseTree ctx = null;

        @SuppressWarnings("unused") // Private constructor for serialization systems ONLY (like Kryo)
        private State() {
            name = null;
        }

        public State(String name) {
            this.name = name;
        }

        public State(ParseTree ctx, String name) {
            this.ctx = ctx;
            this.name = name;
        }

        public String calculatePath(ParseTreeProperty<State> state, PathType type, boolean fakeChild) {
            ParseTree node = ctx;
            path = name;
            if (node == null) {
                return path;
            }
            State parentState = null;

            while (parentState == null) {
                node = node.getParent();
                if (node == null) {
                    return path;
                }
                parentState = state.get(node);
            }

            long counter = 0;
            switch (type) {
                case CHILD:
                    if (!fakeChild) {
                        parentState.child++;
                    }
                    counter = parentState.child;
                    break;
                case COMMENT:
                    if (!fakeChild) {
                        parentState.comment++;
                    }
                    counter = parentState.comment;
                    break;
                case VERSION:
                    if (!fakeChild) {
                        parentState.version++;
                    }
                    counter = parentState.version;
                    break;
                default:
            }

            this.path = parentState.path + ".(" + counter + ')' + name;

            return this.path;
        }
    }

    @SuppressWarnings("unused") // Private constructor for serialization systems ONLY (like Kryo)
    private UserAgentTreeFlattener() {
        analyzer = new MatchMaker.Dummy();
    }

    public UserAgentTreeFlattener(MatchMaker analyzer) {
        this.analyzer = analyzer;
    }

    /**
     * There is no state to clear anymore (all state of a parse is local to that parse).
     */
    public void clear() {
        // Nothing to do
    }

    private volatile boolean verbose = false;

    public void setVerbose(boolean newVerbose) {
        this.verbose = newVerbose;
    }

    public UserAgent parse(String userAgentString) {
        MutableUserAgent userAgent = new MutableUserAgent(userAgentString);
        return parseIntoCleanUserAgent(userAgent, analyzer::inform);
    }

    public MutableUserAgent parse(MutableUserAgent userAgent) {
        userAgent.reset();
        return parseIntoCleanUserAgent(userAgent, analyzer::inform);
    }

    /**
     * Parse the useragent and inform the provided informer about every part that was found.
     * @param userAgent The useragent instance that needs to be parsed
     * @param informer  The receiver of all the parts
     * @return The provided useragent instance
     */
    public MutableUserAgent parse(@NonNull MutableUserAgent userAgent, @NonNull PathInformer informer) {
        userAgent.reset();
        return parseIntoCleanUserAgent(userAgent, informer);
    }

    /**
     * Parse the useragent and return every part that was found.
     *
     * @param userAgent The useragent instance that needs to be parsed
     * @param informer  The receiver of all the parts
     * @return If the parse was valid (i.e. were there any parser errors: true=valid; false=has errors
     */
    private MutableUserAgent parseIntoCleanUserAgent(MutableUserAgent userAgent, PathInformer informer) {
        if (userAgent.getUserAgentString() == null) {
            userAgent.set(SYNTAX_ERROR, "true", 1);
            return userAgent; // Cannot parse this
        }

        // Parse the userAgent into tree
        UserAgentContext userAgentContext = parseUserAgent(userAgent);

        // Walk the tree an inform the calling analyzer about all the nodes found
        new FlattenerWalker(analyzer, informer).walk(userAgentContext, userAgent.hasSyntaxError());
        return userAgent;
    }

//  =================================================================================

    private UserAgentContext parseUserAgent(MutableUserAgent userAgent) {
        String userAgentString = EvilManualUseragentStringHacks.fixIt(userAgent);

        CodePointCharStream input = CharStreams.fromString(userAgentString);
        UserAgentLexer lexer = new UserAgentLexer(input);
        if (!this.verbose) {
            lexer.removeErrorListeners();
        }
        lexer.addErrorListener(userAgent);

        // The parser needs (almost) all tokens anyway, so getting them all first costs (almost) nothing.
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        if (isNestedTooDeeply(tokens.getTokens())) {
            throw new UserAgentTooDeeplyNestedException(
                "The brackets are nested more than " + MAX_NESTING_DEPTH + " levels deep.");
        }

        UserAgentParser parser = new UserAgentParser(tokens);
        // Same parse tree and ambiguity information, but much faster and with a bounded worst case parse time.
        parser.setInterpreter(new UserAgentParserATNSimulator(parser));
        if (!this.verbose) {
            parser.removeErrorListeners();
        }
        parser.addErrorListener(userAgent);

        try {
            return parser.userAgent();
        } catch (StackOverflowError stackOverflowError) {
            // The last line of defense: because of the limit on the nesting depth this should not happen unless the
            // thread has very little stack. The parser (and everything else of this parse) is discarded.
            throw new UserAgentTooDeeplyNestedException("The parser ran out of stack.");
        }
    }

    /**
     * Determine if the brackets in the useragent are nested deeper than {@link #MAX_NESTING_DEPTH}.
     * <p>
     * This uses the tokens (not the characters) because some tokens (like a url) can contain brackets that the parser
     * does not see as brackets. Just like in the parser a closing bracket only closes the innermost open bracket if it
     * is of the same type, all other closing brackets are ignored.
     *
     * @param tokens All tokens of the useragent.
     * @return true if the brackets are nested too deeply.
     */
    static boolean isNestedTooDeeply(@NonNull List<? extends Token> tokens) {
        int[] openBrackets = new int[MAX_NESTING_DEPTH];
        int depth = 0;
        for (Token token : tokens) {
            int type = token.getType();
            switch (type) {
                case UserAgentLexer.BRACEOPEN:
                case UserAgentLexer.BLOCKOPEN:
                case UserAgentLexer.CURLYBRACEOPEN:
                    if (depth == MAX_NESTING_DEPTH) {
                        return true;
                    }
                    openBrackets[depth++] = type;
                    break;
                case UserAgentLexer.BRACECLOSE:
                    depth = close(openBrackets, depth, UserAgentLexer.BRACEOPEN);
                    break;
                case UserAgentLexer.BLOCKCLOSE:
                    depth = close(openBrackets, depth, UserAgentLexer.BLOCKOPEN);
                    break;
                case UserAgentLexer.CURLYBRACECLOSE:
                    depth = close(openBrackets, depth, UserAgentLexer.CURLYBRACEOPEN);
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    // Close the innermost open bracket if it has the provided type. Returns the new depth.
    private static int close(int @NonNull [] openBrackets, int depth, int openType) {
        if (depth > 0 && openBrackets[depth - 1] == openType) {
            return depth - 1;
        }
        return depth;
    }

    //  =================================================================================

    /**
     * Walks a single parse tree and informs the receiver about all nodes.
     * A new instance is created for every parse which makes the flattener threadsafe.
     */
    private static final class FlattenerWalker extends UserAgentBaseListener {
        private final MatchMaker                analyzer;
        private final PathInformer              informer;
        private final ParseTreeProperty<State>  state = new ParseTreeProperty<>();

        FlattenerWalker(MatchMaker analyzer, PathInformer informer) {
            this.analyzer = analyzer;
            this.informer = informer;
        }

        void walk(UserAgentContext userAgentContext, boolean hasSyntaxError) {
            State rootState = new State(AGENT);
            rootState.calculatePath(state, PathType.CHILD, false);
            state.put(userAgentContext, rootState);

            if (hasSyntaxError) {
                inform(null, SYNTAX_ERROR, "true");
            } else {
                inform(null, SYNTAX_ERROR, "false");
            }

            ParseTreeWalker.DEFAULT.walk(this, userAgentContext);
        }

        // =================================================================================

        private String inform(ParseTree ctx, String path) {
            return inform(ctx, path, getSourceText((ParserRuleContext)ctx));
        }

        private String inform(ParseTree ctx, String name, String value) {
            return inform(ctx, ctx, name, value, false);
        }

        private String inform(ParseTree ctx, String name, String value, boolean fakeChild) {
            return inform(ctx, ctx, name, value, fakeChild);
        }

        private String inform(ParseTree stateCtx, ParseTree ctx, String name, String value, boolean fakeChild) {
            String path = name;
            if (stateCtx != null) {
                State myState = new State(stateCtx, name);

                if (!fakeChild) {
                    state.put(stateCtx, myState);
                }

                PathType childType;
                switch (name) {
                    case COMMENTS:
                        childType = PathType.COMMENT;
                        break;
                    case VERSION:
                        childType = PathType.VERSION;
                        break;
                    default:
                        childType = PathType.CHILD;
                }

                path = myState.calculatePath(state, childType, fakeChild);
            }
            informer.inform(path, value, ctx);
            return path;
        }

        // =================================================================================

        @Override
        public void enterUserAgent(UserAgentContext ctx) {
            // In case of a parse error the 'parsed' version of agent can be incomplete
            inform(ctx, AGENT, ctx.start.getTokenSource().getInputStream().toString());
        }

        @Override
        public void enterRootText(RootTextContext ctx) {
            informSubstrings(ctx, TEXT);
        }

        @Override
        public void enterProduct(ProductContext ctx) {
            informSubstrings(ctx, PRODUCT);
        }

        @Override
        public void enterCommentProduct(CommentProductContext ctx) {
            informSubstrings(ctx, PRODUCT);
        }

        @Override
        public void enterProductNameNoVersion(UserAgentParser.ProductNameNoVersionContext ctx) {
            informSubstrings(ctx, PRODUCT);
        }

        @Override
        public void enterProductNameEmail(ProductNameEmailContext ctx) {
            inform(ctx, NAME);
        }

        @Override
        public void enterProductNameUrl(ProductNameUrlContext ctx) {
            inform(ctx, NAME);
        }

        @Override
        public void enterProductNameWords(ProductNameWordsContext ctx) {
            informSubstrings(ctx, NAME);
        }

        @Override
        public void enterProductNameKeyValue(ProductNameKeyValueContext ctx) {
            inform(ctx, "name.(1)keyvalue", ctx.getText(), false);
            informSubstrings(ctx, NAME, true);
        }

        @Override
        public void enterProductNameVersion(ProductNameVersionContext ctx) {
            informSubstrings(ctx, NAME);
        }

        @Override
        public void enterProductNameUuid(ProductNameUuidContext ctx) {
            inform(ctx, NAME);
        }

        @Override
        public void enterProductVersion(ProductVersionContext ctx) {
            enterProductVersion((ParseTree)ctx);
        }

        @Override
        public void enterProductVersionWithCommas(ProductVersionWithCommasContext ctx) {
            enterProductVersion(ctx);
        }

        private void enterProductVersion(ParseTree ctx) {
            ParseTree child = ctx.getChild(0);
            // Only for the SingleVersion edition we want to have splits of the version.
            if (child instanceof SingleVersionContext || child instanceof SingleVersionWithCommasContext) {
                return;
            }

            inform(ctx, VERSION);
        }

        @Override
        public void enterProductVersionSingleWord(UserAgentParser.ProductVersionSingleWordContext ctx) {
            inform(ctx, VERSION);
        }

        @Override
        public void enterSingleVersion(SingleVersionContext ctx) {
            informSubVersions(ctx);
        }

        @Override
        public void enterSingleVersionWithCommas(SingleVersionWithCommasContext ctx) {
            informSubVersions(ctx);
        }

        @Override
        public void enterProductVersionWords(ProductVersionWordsContext ctx) {
            informSubstrings(ctx, VERSION);
        }

        @Override
        public void enterKeyValueProductVersionName(KeyValueProductVersionNameContext ctx) {
            informSubstrings(ctx, VERSION);
        }

        @Override
        public void enterCommentBlock(CommentBlockContext ctx) {
            inform(ctx, COMMENTS);
        }

        @Override
        public void enterCommentEntry(CommentEntryContext ctx) {
            informSubstrings(ctx, "entry");
        }

        private void informSubstrings(ParserRuleContext ctx, String name) {
            informSubstrings(ctx, name, false);
        }

        private void informSubstrings(ParserRuleContext ctx, String name, boolean fakeChild) {
            informSubstrings(ctx, name, fakeChild, WordSplitter.getInstance());
        }

        private void informSubVersions(ParserRuleContext ctx) {
            informSubstrings(ctx, VERSION, false, VersionSplitter.getInstance());
        }

        private void informSubstrings(ParserRuleContext ctx, String name, boolean fakeChild, Splitter splitter) {
            String text = getSourceText(ctx);
            String path = inform(ctx, name, text, fakeChild);
            Set<Range> ranges = analyzer.getRequiredInformRanges(path);

            if (ranges.size() > 4) { // Benchmarks showed this to be the breakeven point. (see below)
                List<Pair<Integer, Integer>> splitList = splitter.createSplitList(text);
                for (Range range : ranges) {
                    String value = splitter.getSplitRange(text, splitList, range);
                    if (value != null) {
                        inform(ctx, ctx, name + range, value, true);
                    }
                }
            } else {
                for (Range range : ranges) {
                    String value = splitter.getSplitRange(text, range);
                    if (value != null) {
                        inform(ctx, ctx, name + range, value, true);
                    }
                }
            }
        }

        // # Ranges | Direct                   |  SplitList
        // 1        |    1.664 ± 0.010  ns/op  |    99.378 ± 1.548  ns/op
        // 2        |   38.103 ± 0.479  ns/op  |   115.808 ± 1.055  ns/op
        // 3        |  109.023 ± 0.849  ns/op  |   141.473 ± 6.702  ns/op
        // 4        |  162.917 ± 1.842  ns/op  |   166.120 ± 7.166  ns/op  <-- Break even
        // 5        |  264.877 ± 6.264  ns/op  |   176.334 ± 3.999  ns/op
        // 6        |  356.914 ± 2.573  ns/op  |   196.640 ± 1.306  ns/op
        // 7        |  446.930 ± 3.329  ns/op  |   215.499 ± 3.410  ns/op
        // 8        |  533.153 ± 2.250  ns/op  |   233.241 ± 5.311  ns/op
        // 9        |  519.130 ± 3.495  ns/op  |   250.921 ± 6.107  ns/op

        @Override
        public void enterMultipleWords(MultipleWordsContext ctx) {
            informSubstrings(ctx, TEXT);
        }

        @Override
        public void enterKeyValue(KeyValueContext ctx) {
            inform(ctx, KEYVALUE);
        }

        @Override
        public void enterKeyWithoutValue(UserAgentParser.KeyWithoutValueContext ctx) {
            inform(ctx, KEYVALUE);
        }

        @Override
        public void enterKeyName(KeyNameContext ctx) {
            informSubstrings(ctx, KEY);
        }

        @Override
        public void enterKeyValueVersionName(KeyValueVersionNameContext ctx) {
            informSubstrings(ctx, VERSION);
        }

        @Override
        public void enterVersionWords(VersionWordsContext ctx) {
            informSubstrings(ctx, TEXT);
        }

        @Override
        public void enterSiteUrl(SiteUrlContext ctx) {
            inform(ctx, URL, ctx.url.getText());
        }

        @Override
        public void enterUuId(UuIdContext ctx) {
            inform(ctx, UUID, ctx.uuid.getText());
        }

        @Override
        public void enterEmailAddress(EmailAddressContext ctx) {
            inform(ctx, EMAIL, ctx.email.getText());
        }

        @Override
        public void enterBase64(Base64Context ctx) {
            inform(ctx, BASE64, ctx.value.getText());
        }

        @Override
        public void enterEmptyWord(EmptyWordContext ctx) {
            inform(ctx, TEXT, "");
        }
    }

    @Override
    public String toString() {
        return "UserAgentTreeFlattener{" +
            " verbose=" + verbose +
            "} ";
    }
}
