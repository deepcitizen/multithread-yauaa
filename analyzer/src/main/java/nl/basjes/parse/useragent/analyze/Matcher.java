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
import nl.basjes.parse.useragent.UserAgent.MutableUserAgent;
import nl.basjes.parse.useragent.config.MatcherConfig;
import nl.basjes.parse.useragent.config.MatcherConfig.ConfigLine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static nl.basjes.parse.useragent.UserAgent.SET_ALL_FIELDS;

/**
 * A Matcher is a single rule: a set of actions that must all be valid for the extracted values to be used.
 * <p>
 * After initialization a Matcher is immutable and can be shared by many threads at the same time.
 * All state that changes during the analysis of a single useragent is kept in the {@link ParseState}.
 */
public class Matcher implements Serializable {
    private static final Logger LOG = LogManager.getLogger(Matcher.class);

    private static final MatcherAction[] NO_ACTIONS = new MatcherAction[0];

    private final MatchMaker analyzer;
    private final List<MatcherVariableAction> variableActions;
    private final List<MatcherAction> dynamicActions;
    private final List<MatcherAction> fixedStringActions;

    // The fields this matcher can produce. This is a template that is only modified during construction and
    // initialization (the fixed values are put in here). At runtime it is never modified.
    private MutableUserAgent newValuesUserAgent = null;

    // The wanted field names of the analyzer (null == all). Needed to reproduce the result for debugging.
    private final Collection<String> wantedFieldNames;

    private long actionsThatRequireInput;
    private final boolean verbose;

    public String getMatcherSourceLocation() {
        return matcherSourceLocation;
    }

    private String sourceFileName;
    private Integer sourceFileLineNumber;
    public String getSourceFileName() {
        return sourceFileName;
    }
    public Integer getSourceFileLineNumber() {
        return sourceFileLineNumber;
    }

    // Used for error reporting: The filename and line number where the config was located.
    private String matcherSourceLocation;

    // ---------------------------------------------------------------------------------------------------
    // Runtime structures: Derived from the configuration by the MatchMaker after the initialization.
    // These are transient because they are (re)created after deserialization.

    // The index of this matcher in the MatchMaker (used to find the state in a ParseState).
    private transient int matcherIndex = -1;
    // A matcher that does not need any input is evaluated for every parse.
    private transient boolean zeroInputMatcher = false;
    private transient MatcherAction[] dynamicActionsArray = NO_ACTIONS;
    // The names (sorted) of the fields this matcher outputs with the default value and confidence of each.
    private transient String[] resultFieldNames = new String[0];
    private transient String[] resultTemplateValues = new String[0];
    private transient long[]   resultTemplateConfidences = new long[0];

    @SuppressWarnings("unused") // Private constructor for serialization systems ONLY (like Kryo)
    private Matcher() {
        this.analyzer = null;
        this.fixedStringActions = new ArrayList<>();
        this.variableActions = new ArrayList<>();
        this.dynamicActions = new ArrayList<>();
        this.wantedFieldNames = null;
        this.verbose = false;
    }

    // Package private constructor for testing purposes only
    Matcher(MatchMaker analyzer) {
        this.analyzer = analyzer;
        this.fixedStringActions = new ArrayList<>();
        this.variableActions = new ArrayList<>();
        this.dynamicActions = new ArrayList<>();
        this.wantedFieldNames = null;
        this.verbose = false;
    }

    public void destroy() {
        dynamicActions.forEach(MatcherAction::destroy);
        dynamicActions.clear();

        fixedStringActions.forEach(MatcherAction::destroy);
        fixedStringActions.clear();

        dynamicActionsArray = NO_ACTIONS;
    }

    public Map<String, Map<String, String>> getLookups() {
        return analyzer.getLookups();
    }

    public Map<String, Set<String>> getLookupSets() {
        return analyzer.getLookupSets();
    }

    @SuppressWarnings("this-escape")
    public Matcher(MatchMaker analyzer,
                   Collection<String> wantedFieldNames,
                   MatcherConfig matcherConfig) throws UselessMatcherException {
        this.analyzer = analyzer;
        this.fixedStringActions = new ArrayList<>();
        this.variableActions = new ArrayList<>();
        this.dynamicActions = new ArrayList<>();
        this.wantedFieldNames = wantedFieldNames;
        this.newValuesUserAgent = new MutableUserAgent(wantedFieldNames);

        sourceFileName = matcherConfig.getMatcherSourceFilename();
        sourceFileLineNumber = matcherConfig.getMatcherSourceLineNumber();
        matcherSourceLocation = sourceFileName + ':' + sourceFileLineNumber;

        List<String> options = matcherConfig.getOptions();
        verbose = options != null && options.contains("verbose");

        boolean hasActiveExtractConfigs = false;
        boolean hasDefinedExtractConfigs = false;

        if (verbose) {
            LOG.info("---------------------------");
            LOG.info("- MATCHER -");
        }

        for (ConfigLine configLine : matcherConfig.getConfigLines()) {
            if (verbose) {
                LOG.info("{}: {}", configLine.getType(), configLine.getExpression());
            }
            switch (configLine.getType()) {
                case VARIABLE:
                    variableActions.add(new MatcherVariableAction(configLine.getAttribute(), configLine.getExpression(), this));
                    break;
                case REQUIRE:
                    dynamicActions.add(new MatcherRequireAction(configLine.getExpression(), this));
                    break;
                case FAIL_IF_FOUND:
                    dynamicActions.add(new MatcherFailIfFoundAction(configLine.getExpression(), this));
                    break;
                case EXTRACT:
                    hasDefinedExtractConfigs = true;
                    // If we have a restriction on the wanted fields we check if this one is needed at all
                    if (wantedFieldNames == null || wantedFieldNames.contains(configLine.getAttribute())) {
                        MatcherExtractAction action =
                            new MatcherExtractAction(configLine.getAttribute(), configLine.getConfidence(), configLine.getExpression(), this);
                        dynamicActions.add(action);

                        // Make sure the field actually exists
                        newValuesUserAgent.set(configLine.getAttribute(), "Dummy", -9999);
                        action.setResultAgentField(newValuesUserAgent.get(configLine.getAttribute()));

                        hasActiveExtractConfigs = true;
                    } else {
                        dynamicActions.add(new MatcherDemotedExtractAction(configLine.getExpression(), this));
                    }
                    break;
                default:
                    break;
            }
        }

        if (!hasDefinedExtractConfigs) {
            throw new InvalidParserConfigurationException("Matcher does not extract anything:" + matcherSourceLocation);
        }

        if (!hasActiveExtractConfigs) {
            throw new UselessMatcherException("Does not extract any wanted fields" + matcherSourceLocation);
        }

    }

    public static class MatcherDemotedExtractAction extends MatcherRequireAction {
        @SuppressWarnings("unused") // Private constructor for serialization systems ONLY (like Kryo)
        private MatcherDemotedExtractAction() {
            super();
        }

        MatcherDemotedExtractAction(String config, Matcher matcher) {
            super(config, matcher);
        }
    }

    public void initialize() {
        long newEntries = 0;
        long initStart = System.nanoTime();
        try {
            for (MatcherVariableAction variableAction : variableActions) {
                newEntries += variableAction.initialize();
            }
        } catch (InvalidParserConfigurationException e) {
            throw new InvalidParserConfigurationException("Syntax error.(" + matcherSourceLocation + ")", e);
        }

        Set<MatcherAction> uselessRequireActions = new HashSet<>();
        for (MatcherAction dynamicAction : dynamicActions) {
            try {
                newEntries += dynamicAction.initialize();
            } catch (InvalidParserConfigurationException e) {
                // Ignore fixed values in require IFF this was caused by moving a fixed value extract
                // to a require when asking for limited set of values.
                if (!(dynamicAction instanceof MatcherDemotedExtractAction) ||
                    !e.getMessage().startsWith("It is useless to put a fixed value")) {
                    throw new InvalidParserConfigurationException("Syntax error.(" + matcherSourceLocation + ")" + e.getMessage(), e);
                }
                uselessRequireActions.add(dynamicAction);
            }
        }

        for (MatcherAction action: dynamicActions) {
            if (action instanceof MatcherExtractAction) {
                MatcherExtractAction extractAction = (MatcherExtractAction) action;
                if (extractAction.isFixedValue()) {
                    fixedStringActions.add(action);
                    // The fixed value is put in the template of the results of this matcher.
                    extractAction.applyFixedValue();
                }
            }
        }

        fixedStringActions.forEach(dynamicActions::remove);
        uselessRequireActions.forEach(dynamicActions::remove);

        // Verify that a variable only contains the variables that have been defined BEFORE it (also not referencing itself).
        // If all is ok we link them
        Set<MatcherAction> seenVariables = new HashSet<>(variableActions.size());
        for (MatcherVariableAction variableAction: variableActions) {
            seenVariables.add(variableAction); // Add myself
            Set<MatcherAction> interestedActions = informMatcherActionsAboutVariables.get(variableAction.getVariableName());
            if (interestedActions != null && !interestedActions.isEmpty()) {
                variableAction.setInterestedActions(interestedActions);
                for (MatcherAction interestedAction : interestedActions) {
                    if (seenVariables.contains(interestedAction)) {
                        throw new InvalidParserConfigurationException(
                            "Syntax error (" + matcherSourceLocation + "): The line >>" + interestedAction + "<< " +
                            "is referencing variable @"+variableAction.getVariableName()+ " which is not defined yet.");
                    }
                }
            }
        }

        // Check if any variable was requested that was not defined.
        Set<String> missingVariableNames = new HashSet<>();
        Set<String> seenVariableNames = new HashSet<>();
        seenVariables.forEach(m -> seenVariableNames.add(((MatcherVariableAction)m).getVariableName()));
        for (String variableName: informMatcherActionsAboutVariables.keySet()) {
            if (!seenVariableNames.contains(variableName)) {
                missingVariableNames.add(variableName);
            }
        }
        if (!missingVariableNames.isEmpty()) {
            throw new InvalidParserConfigurationException(
                "Syntax error (" + matcherSourceLocation + "): Used, yet undefined variables: " + missingVariableNames);
        }

        // Make sure the variable actions are BEFORE the rest in the list
        dynamicActions.addAll(0, variableActions);

        actionsThatRequireInput = countActionsThatMustHaveMatches(dynamicActions);

        long initFinish = System.nanoTime();
        if (newEntries > 3000) {
            LOG.warn("Large matcher: {} in {} ms:.({})", newEntries, (initFinish-initStart)/1000000, matcherSourceLocation);
        }

        if (verbose) {
            LOG.info("---------------------------");
        }

    }

    private long countActionsThatMustHaveMatches(List<? extends MatcherAction> actions) {
        long actionsThatMustHaveMatches = 0;
        for (MatcherAction action : actions) {
            // If an action exists which without any data can be valid, then we must force the evaluation
            if (action.mustHaveMatches()) {
                actionsThatMustHaveMatches++;
            }
        }
        return actionsThatMustHaveMatches;
    }

    /**
     * Builds the (transient) runtime structures of this matcher. Must be called after {@link #initialize()}.
     * @param newMatcherIndex The index of this matcher in the MatchMaker.
     * @param firstActionIndex The first free index for the actions.
     * @return The first free index for the actions after the actions of this matcher.
     */
    int initializeRuntime(int newMatcherIndex, int firstActionIndex) {
        matcherIndex = newMatcherIndex;
        zeroInputMatcher = actionsThatRequireInput == 0;
        dynamicActionsArray = dynamicActions.toArray(NO_ACTIONS);

        int actionIndex = firstActionIndex;
        for (MatcherAction action : dynamicActionsArray) {
            action.initializeRuntime(actionIndex++, matcherIndex, zeroInputMatcher);
        }

        // The results are applied in the same order as the iteration over all fields of a MutableUserAgent (sorted by name).
        List<String> fieldNames = newValuesUserAgent.getAllFieldNames();
        int fields = fieldNames.size();
        String[] newResultFieldNames = new String[fields];
        String[] newResultTemplateValues = new String[fields];
        long[]   newResultTemplateConfidences = new long[fields];
        for (int i = 0; i < fields; i++) {
            String fieldName = fieldNames.get(i);
            MutableAgentField field = newValuesUserAgent.get(fieldName);
            newResultFieldNames[i] = fieldName;
            newResultTemplateValues[i] = field.getRawValue();
            newResultTemplateConfidences[i] = field.getConfidence();
        }
        resultFieldNames = newResultFieldNames;
        resultTemplateValues = newResultTemplateValues;
        resultTemplateConfidences = newResultTemplateConfidences;

        for (MatcherAction action : dynamicActionsArray) {
            linkResultSlot(action, fieldNames);
        }
        for (MatcherAction action : fixedStringActions) {
            // A fixed value never receives any input so it never has any state.
            action.initializeRuntime(-1, matcherIndex, zeroInputMatcher);
            linkResultSlot(action, fieldNames);
        }
        return actionIndex;
    }

    private static void linkResultSlot(MatcherAction action, List<String> fieldNames) {
        if (action instanceof MatcherExtractAction) {
            MatcherExtractAction extractAction = (MatcherExtractAction) action;
            int slot = fieldNames.indexOf(extractAction.getAttribute());
            if (slot < 0) {
                throw new IllegalStateException("No result slot for " + extractAction.getAttribute() + " in " + extractAction);
            }
            extractAction.setResultSlot(slot);
        }
    }

    int getMatcherIndex() {
        return matcherIndex;
    }

    boolean isZeroInputMatcher() {
        return zeroInputMatcher;
    }

    public Set<String> getAllPossibleFieldNames() {
        Set<String> results = new TreeSet<>();
        results.addAll(getAllPossibleFieldNames(dynamicActions));
        results.addAll(getAllPossibleFieldNames(fixedStringActions));
        results.remove(SET_ALL_FIELDS);
        return results;
    }

    private Set<String> getAllPossibleFieldNames(List<MatcherAction> actions) {
        Set<String> results = new TreeSet<>();
        for (MatcherAction action: actions) {
            if (action instanceof MatcherExtractAction) {
                MatcherExtractAction extractAction = (MatcherExtractAction)action;
                results.add(extractAction.getAttribute());
            }
        }
        return results;
    }

    public void lookingForRange(String treeName, WordRangeVisitor.Range range) {
        analyzer.lookingForRange(treeName, range);
    }

    public void informMeAbout(MatcherAction matcherAction, String keyPattern) {
        analyzer.informMeAbout(matcherAction, keyPattern);
    }

    public void informMeAboutPrefix(MatcherAction matcherAction, String keyPattern, String prefix) {
        analyzer.informMeAboutPrefix(matcherAction, keyPattern, prefix);
    }

    private final Map<String, Set<MatcherAction>> informMatcherActionsAboutVariables = new HashMap<>(8);

    void informMeAboutVariable(MatcherAction matcherAction, String variableName) {
        Set<MatcherAction> analyzerSet = informMatcherActionsAboutVariables
            .computeIfAbsent(variableName, k -> new LinkedHashSet<>());
        analyzerSet.add(matcherAction);
    }

    /**
     * Fires all matcher actions.
     * IFF all success then we tell the userAgent
     *
     * @param state     The state of the current parse.
     * @param userAgent The useragent that needs to analyzed
     */
    void analyze(@NonNull ParseState state, @NonNull MutableUserAgent userAgent) {
        if (verbose) {
            state.startMatcherResults(resultTemplateValues, resultTemplateConfidences);
            LOG.info("");
            LOG.info("--- Matcher.({}) ------------------------", matcherSourceLocation);
            LOG.info("ANALYSE ----------------------------");
            boolean good = true;
            for (MatcherAction action : dynamicActionsArray) {
                if (action.cannotBeValid(state)) {
                    LOG.error("CANNOT BE VALID : {}", action.getMatchExpression());
                    good = false;
                }
            }
            for (MatcherAction action : dynamicActionsArray) {
                if (!action.obtainResult(state)) {
                    LOG.error("FAILED : {}", action.getMatchExpression());
                    good = false;
                }
            }
            if (good) {
                LOG.info("COMPLETE ----------------------------");
            } else {
                LOG.info("INCOMPLETE ----------------------------");
                return;
            }
        } else {
            if (actionsThatRequireInput != state.getReceivedInput(this)) {
                return;
            }
            state.startMatcherResults(resultTemplateValues, resultTemplateConfidences);
            for (MatcherAction action : dynamicActionsArray) {
                if (action.obtainResult(state)) {
                    continue;
                }
                return; // If one of them is bad we skip the rest
            }
        }
        userAgent.setMatcherResults(this, resultFieldNames, state.getResultValues(), state.getResultConfidences());
    }

    /**
     * Creates a new MutableUserAgent with the provided results of this Matcher. Only used for debugging.
     * @param values The (raw) values of all fields of this matcher in the same order as the resultFieldNames
     * @param confidences The confidences of all fields of this matcher in the same order as the resultFieldNames
     * @return A new MutableUserAgent which only holds the result of this Matcher.
     */
    @NonNull
    public MutableUserAgent createResultUserAgent(@Nullable String @NonNull [] values, long @NonNull [] confidences) {
        MutableUserAgent result = new MutableUserAgent(wantedFieldNames);
        for (int i = 0; i < resultFieldNames.length; i++) {
            result.get(resultFieldNames[i]).setValueForced(values[i], confidences[i]);
        }
        return result;
    }

    public boolean getVerbose() {
        return verbose;
    }

    public long getActionsThatRequireInput() {
        return actionsThatRequireInput;
    }

    /**
     * Debugging only: The number of actions that require input and have received input during the most recent parse.
     * @return The number of actions that require input and have received input.
     */
    public long getActionsThatRequireInputAndReceivedInput() {
        ParseState state = getDebugParseState();
        if (state == null) {
            return 0;
        }
        return state.getReceivedInput(this);
    }

    protected void failImmediately(@NonNull ParseState state) {
        state.failImmediately(this);
    }

    /**
     * The state of the most recent parse (only meaningful for debugging in a single threaded situation).
     * @return The ParseState of the most recent parse or null if not available.
     */
    @Nullable
    ParseState getDebugParseState() {
        if (analyzer == null) {
            return null;
        }
        return analyzer.getDebugParseState();
    }

    /**
     * Debugging only.
     * The temporary verbose flag is now part of each parse (see {@link MutableUserAgent#setDebug(boolean)}).
     * @param newVerbose Ignored.
     */
    public void setVerboseTemporarily(boolean newVerbose) {
        // Nothing to do: The temporary verbose flag is now held in the ParseState of each parse.
    }

    /**
     * There is no state to reset anymore (it is all in the ParseState).
     */
    public void reset() {
        // Nothing to do: All state is held in the ParseState of each parse.
    }

    /**
     * Debugging only: The matches of all actions found during the most recent parse.
     * @return The list of matches.
     */
    public List<MatchesList.Match> getMatches() {
        return getMatches(getDebugParseState());
    }

    @NonNull
    List<MatchesList.Match> getMatches(@Nullable ParseState state) {
        List<MatchesList.Match> allMatches = new ArrayList<>(128);
        if (state == null) {
            return allMatches;
        }
        for (MatcherAction action : dynamicActions) {
            allMatches.addAll(state.getMatches(action));
        }
        return allMatches;
    }

    /**
     * Debugging only: The matches of all actions that were actually used during the most recent parse.
     * @return The list of matches.
     */
    public List<MatchesList.Match> getUsedMatches() {
        return getUsedMatches(getDebugParseState());
    }

    @NonNull
    List<MatchesList.Match> getUsedMatches(@Nullable ParseState state) {
        List<MatchesList.Match> allMatches = new ArrayList<>(128);
        if (state == null) {
            return allMatches;
        }
        for (MatcherAction action : dynamicActions) {
            if (action.cannotBeValid(state)) {
                return new ArrayList<>(); // There is NO way one of them is valid
            }
        }
        state.startMatcherResults(resultTemplateValues, resultTemplateConfidences);
        for (MatcherAction action : dynamicActions) {
            if (!action.obtainResult(state)) {
                return new ArrayList<>(); // There is NO way one of them is valid
            } else {
                allMatches.addAll(state.getMatches(action));
            }
        }
        return allMatches;
    }

    @Override
    public String toString() {
        ParseState state = getDebugParseState();
        StringBuilder sb = new StringBuilder(512);
        sb.append("MATCHER.(").append(matcherSourceLocation).append("):\n")
          .append("    VARIABLE:\n");
        for (MatcherAction action : dynamicActions) {
            if (action instanceof MatcherVariableAction) {
                sb.append("        @").append(((MatcherVariableAction) action).getVariableName())
                    .append(":    ").append(action.getMatchExpression()).append('\n');
                sb.append("        -->").append(action.getMatches(state).toStrings()).append('\n');
            }
        }
        sb.append("    REQUIRE:\n");
        for (MatcherAction action : dynamicActions) {
            if (action instanceof MatcherRequireAction) {
                sb.append("        ").append(action.getMatchExpression()).append('\n');
                sb.append("        -->").append(action.getMatches(state).toStrings()).append('\n');
            }
        }
        sb.append("    FAIL_IF_FOUND:\n");
        for (MatcherAction action : dynamicActions) {
            if (action instanceof MatcherFailIfFoundAction) {
                sb.append("        ").append(action.getMatchExpression()).append('\n');
                sb.append("        -->").append(action.getMatches(state).toStrings()).append('\n');
            }
        }
        sb.append("    EXTRACT:\n");
        for (MatcherAction action : dynamicActions) {
            if (action instanceof MatcherExtractAction) {
                sb.append("        ").append(action).append('\n');
                sb.append("        -->").append(action.getMatches(state)).append('\n');
            }
        }
        for (MatcherAction action : fixedStringActions) {
            sb.append("        ").append(action).append('\n');
        }
        return sb.toString();
    }
}
