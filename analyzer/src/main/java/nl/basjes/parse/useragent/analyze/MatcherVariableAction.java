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

import nl.basjes.parse.useragent.analyze.treewalker.steps.WalkList.WalkResult;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Set;

@SuppressWarnings("this-escape")
public class MatcherVariableAction extends MatcherAction {
    private static final Logger LOG = LogManager.getLogger(MatcherVariableAction.class);

    private final String variableName;
    private Set<MatcherAction> interestedActions;

    @SuppressWarnings("unused") // Private constructor for serialization systems ONLY (like Kryo)
    private MatcherVariableAction() {
        variableName = null;
    }

    public MatcherVariableAction(String variableName, String config, Matcher matcher) {
        this.variableName = variableName;
        init(config, matcher);
    }

    protected ParserRuleContext parseWalkerExpression(UserAgentTreeWalkerParser parser) {
        return parser.matcherVariable();
    }

    protected void setFixedValue(String fixedValue) {
        throw new InvalidParserConfigurationException(
            "It is useless to put a fixed value \"" + fixedValue + "\" in the variable section.");
    }

    public String getVariableName() {
        return variableName;
    }

    @Override
    protected void inform(@NonNull ParseState state, @Nullable String key, @NonNull WalkResult newlyFoundValue) {
        boolean verboseParse = isVerbose(state);
        if (verboseParse) {
            LOG.info("INFO  : VARIABLE ({}): {}", variableName, key);
            LOG.info("NEED  : VARIABLE ({}): {}", variableName, getMatchExpression());
        }
        /*
         * We know the tree is parsed from left to right.
         * This is also the priority in the fields.
         * So we always use the first value we find.
         */
        if (state.getFoundValue(this) == null) {
            state.setFoundValue(this, newlyFoundValue);
            if (verboseParse) {
                LOG.info("KEPT  : VARIABLE ({}): {}", variableName, key);
            }

            if (interestedActions != null && !interestedActions.isEmpty()) {
                for (MatcherAction action : interestedActions) {
                    action.inform(state, variableName, newlyFoundValue.getValue(), newlyFoundValue.getTree());
                }
            }
        }
    }

    @Override
    boolean obtainResult(@NonNull ParseState state) {
        processInformedMatches(state);
        return state.getFoundValue(this) != null;
    }

    @Override
    public String toString() {
        return "Variable.("+matcher.getMatcherSourceLocation()+"): (" + variableName + "): " + getMatchExpression();
    }

    public void setInterestedActions(Set<MatcherAction> newInterestedActions) {
        this.interestedActions = newInterestedActions;
    }
}
