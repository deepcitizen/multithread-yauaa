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

import nl.basjes.parse.useragent.AgentField.MutableAgentField;
import nl.basjes.parse.useragent.analyze.treewalker.steps.WalkList.WalkResult;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("this-escape")
public class MatcherExtractAction extends MatcherAction {
    private static final Logger LOG = LogManager.getLogger(MatcherExtractAction.class);

    private final String attribute;
    private final long confidence;
    private String fixedValue = null;
    private final String                       expression;
    // The field in the result template of the matcher (only used to store the fixed value during initialization).
    private       MutableAgentField resultAgentField;
    // Where in the results of the matcher the value must be put (assigned by the Matcher at runtime initialization).
    private transient int resultSlot = -1;

    @SuppressWarnings("unused") // Private constructor for serialization systems ONLY (like Kryo)
    private MatcherExtractAction() {
        attribute = null;
        confidence = -1;
        expression = null;
    }

    public MatcherExtractAction(String attribute, long confidence, String config, Matcher matcher) {
        this.attribute = attribute;
        this.confidence = confidence;
        expression = config;
        init(config, matcher);
    }

    public void setResultAgentField(MutableAgentField newResultAgentField){
        resultAgentField = newResultAgentField;
    }

    void setResultSlot(int newResultSlot) {
        resultSlot = newResultSlot;
    }

    /**
     * Puts the fixed value (if this is a fixed value) in the result template of the matcher.
     * Only called during the initialization of the matcher.
     */
    void applyFixedValue() {
        if (fixedValue != null) {
            if (verbose) {
                LOG.info("Set fixedvalue ({})[{}]: {}", attribute, confidence, fixedValue);
            }
            resultAgentField.setValueForced(fixedValue, confidence);
        }
    }

    protected ParserRuleContext parseWalkerExpression(UserAgentTreeWalkerParser parser) {
        return parser.matcherExtract();
    }

    public boolean isFixedValue() {
        return this.fixedValue != null;
    }

    protected void setFixedValue(String newFixedValue) {
        if (verbose) {
            LOG.info("-- set Fixed value({} , {} , {})", attribute, confidence, newFixedValue);
        }
        this.fixedValue = newFixedValue;
    }

    public String getAttribute() {
        return attribute;
    }

    @Override
    protected void inform(@NonNull ParseState state, @Nullable String key, @NonNull WalkResult newlyFoundValue) {
        boolean verboseParse = isVerbose(state);
        if (verboseParse) {
            LOG.info("INFO  : EXTRACT ({}): {}", attribute, key);
            LOG.info("NEED  : EXTRACT ({}): {}", attribute, getMatchExpression());
        }
        /*
         * We know the tree is parsed from left to right.
         * This is also the priority in the fields.
         * So we always use the first value we find.
         */
        if (state.getFoundValue(this) == null) {
            state.setFoundValue(this, newlyFoundValue.getValue());
            if (verboseParse) {
                LOG.info("KEPT  : EXTRACT ({}): {}", attribute, key);
            }
        }
    }

    @Override
    boolean obtainResult(@NonNull ParseState state) {
        processInformedMatches(state);
        boolean verboseParse = isVerbose(state);
        if (fixedValue != null) {
            if (verboseParse) {
                LOG.info("Set fixedvalue ({})[{}]: {}", attribute, confidence, fixedValue);
            }
            state.setMatcherResult(resultSlot, fixedValue, confidence);
            return true;
        }
        String foundValue = (String) state.getFoundValue(this);
        if (foundValue != null) {
            if (verboseParse) {
                LOG.info("Set parsevalue ({})[{}]: {}", attribute, confidence, foundValue);
            }
            state.setMatcherResult(resultSlot, foundValue, confidence);
            return true;
        }
        if (verboseParse) {
            LOG.info("Nothing found for {}", attribute);
        }

        return false;
    }

    @Override
    public String toString() {
        if (isFixedValue()) {
            return "Extract FIXED.("+matcher.getMatcherSourceLocation()+"): (" + attribute + ", " + confidence + ") =   \"" + fixedValue + "\"";
        } else {
            return "Extract DYNAMIC.("+matcher.getMatcherSourceLocation()+"): (" + attribute + ", " + confidence + "):    " + expression;
        }
    }
}
