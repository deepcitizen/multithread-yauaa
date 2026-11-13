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

import com.esotericsoftware.kryo.DefaultSerializer;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import nl.basjes.parse.useragent.UserAgent.MutableUserAgent;
import nl.basjes.parse.useragent.analyze.WordRangeVisitor.Range;
import nl.basjes.parse.useragent.calculate.CalculateAgentClass;
import nl.basjes.parse.useragent.calculate.CalculateAgentEmail;
import nl.basjes.parse.useragent.calculate.CalculateAgentName;
import nl.basjes.parse.useragent.calculate.CalculateDeviceBrand;
import nl.basjes.parse.useragent.calculate.CalculateDeviceName;
import nl.basjes.parse.useragent.calculate.CalculateNetworkType;
import nl.basjes.parse.useragent.calculate.ConcatNONDuplicatedCalculator;
import nl.basjes.parse.useragent.calculate.FieldCalculator;
import nl.basjes.parse.useragent.calculate.MacOSXMajorVersionCalculator;
import nl.basjes.parse.useragent.calculate.MajorVersionCalculator;
import nl.basjes.parse.useragent.calculate.VersionCleanupCalculator;
import nl.basjes.parse.useragent.config.AnalyzerConfig;
import nl.basjes.parse.useragent.config.AnalyzerConfigHolder;
import nl.basjes.parse.useragent.config.ConfigLoader;
import nl.basjes.parse.useragent.config.MatcherConfig;
import nl.basjes.parse.useragent.parse.UserAgentTooDeeplyNestedException;
import nl.basjes.parse.useragent.parse.UserAgentTreeFlattener;
import nl.basjes.parse.useragent.utils.KryoConfig;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static nl.basjes.parse.useragent.UserAgent.AGENT_CLASS;
import static nl.basjes.parse.useragent.UserAgent.AGENT_NAME;
import static nl.basjes.parse.useragent.UserAgent.AGENT_NAME_VERSION;
import static nl.basjes.parse.useragent.UserAgent.AGENT_NAME_VERSION_MAJOR;
import static nl.basjes.parse.useragent.UserAgent.AGENT_VERSION;
import static nl.basjes.parse.useragent.UserAgent.AGENT_VERSION_MAJOR;
import static nl.basjes.parse.useragent.UserAgent.DEVICE_BRAND;
import static nl.basjes.parse.useragent.UserAgent.DEVICE_CLASS;
import static nl.basjes.parse.useragent.UserAgent.DEVICE_NAME;
import static nl.basjes.parse.useragent.UserAgent.DEVICE_VERSION;
import static nl.basjes.parse.useragent.UserAgent.HACKER_ATTACK_VECTOR;
import static nl.basjes.parse.useragent.UserAgent.HACKER_TOOLKIT;
import static nl.basjes.parse.useragent.UserAgent.LAYOUT_ENGINE_CLASS;
import static nl.basjes.parse.useragent.UserAgent.LAYOUT_ENGINE_NAME;
import static nl.basjes.parse.useragent.UserAgent.LAYOUT_ENGINE_NAME_VERSION;
import static nl.basjes.parse.useragent.UserAgent.LAYOUT_ENGINE_NAME_VERSION_MAJOR;
import static nl.basjes.parse.useragent.UserAgent.LAYOUT_ENGINE_VERSION;
import static nl.basjes.parse.useragent.UserAgent.LAYOUT_ENGINE_VERSION_MAJOR;
import static nl.basjes.parse.useragent.UserAgent.MutableUserAgent.isSystemField;
import static nl.basjes.parse.useragent.UserAgent.OPERATING_SYSTEM_CLASS;
import static nl.basjes.parse.useragent.UserAgent.OPERATING_SYSTEM_NAME;
import static nl.basjes.parse.useragent.UserAgent.OPERATING_SYSTEM_NAME_VERSION;
import static nl.basjes.parse.useragent.UserAgent.OPERATING_SYSTEM_NAME_VERSION_MAJOR;
import static nl.basjes.parse.useragent.UserAgent.OPERATING_SYSTEM_VERSION;
import static nl.basjes.parse.useragent.UserAgent.OPERATING_SYSTEM_VERSION_MAJOR;
import static nl.basjes.parse.useragent.UserAgent.PRE_SORTED_FIELDS_LIST;
import static nl.basjes.parse.useragent.UserAgent.REMARKABLE_PATTERN;
import static nl.basjes.parse.useragent.UserAgent.SET_ALL_FIELDS;
import static nl.basjes.parse.useragent.UserAgent.SYNTAX_ERROR;
import static nl.basjes.parse.useragent.UserAgent.WEBVIEW_APP_NAME;
import static nl.basjes.parse.useragent.UserAgent.WEBVIEW_APP_NAME_VERSION;
import static nl.basjes.parse.useragent.UserAgent.WEBVIEW_APP_NAME_VERSION_MAJOR;
import static nl.basjes.parse.useragent.UserAgent.WEBVIEW_APP_VERSION;
import static nl.basjes.parse.useragent.UserAgent.WEBVIEW_APP_VERSION_MAJOR;
import static nl.basjes.parse.useragent.clienthints.ClientHintsAnalyzer.extraDependenciesNeededByClientCalculator;
import static nl.basjes.parse.useragent.utils.YauaaVersion.logVersion;

/**
 * The core of the analyzer: It holds all the rules and does the actual analysis of a useragent.
 * <p>
 * This class is threadsafe and the parse method is NOT synchronized: Any number of threads can parse at the same time
 * using a single instance. All rules (matchers, actions, lookups and hashmaps) are immutable after the initialization
 * and all mutable state of a single parse is held in a {@link ParseState} which is taken from (and returned to) a small
 * lock free pool.
 */
@DefaultSerializer(UserAgentStringMatchMaker.KryoSerializer.class)
public class UserAgentStringMatchMaker implements MatchMaker, AnalyzerConfigHolder, Serializable {
    // We set this to 1000000 always.
    // Why?
    // At the time of writing this the actual HashMap size needed about 410K entries.
    // To keep the bins small the load factor of 0.75 already puts us at the capacity of 1048576
    private static final int INFORM_ACTIONS_HASHMAP_CAPACITY = 1000000;

    private static final Logger LOG = LogManager.getLogger(UserAgentStringMatchMaker.class);
    private final ArrayList<Matcher> allMatchers = new ArrayList<>(5000);
    private final ArrayList<Matcher> zeroInputMatchers = new ArrayList<>(100);

    public List<Matcher> getAllMatchers() {
        return allMatchers;
    }

    /**
     * Only intended for debugging and testing in a single threaded situation.
     * @return The matchers that received input during the most recent parse.
     */
    public MatcherList getTouchedMatchers() {
        ParseState state = getDebugParseState();
        if (state == null) {
            return new MatcherList(1);
        }
        return state.getTouchedMatchers();
    }

    private final Map<String, Set<MatcherAction>> informMatcherActions = new LinkedHashMap<>(INFORM_ACTIONS_HASHMAP_CAPACITY);

    private boolean showMatcherStats;

    // If we want ALL fields this is null. If we only want specific fields this is a list of names.
    protected Set<String> wantedFieldNames = null; // NOSONAR: Only accessed via Builder.

    protected UserAgentTreeFlattener flattener;

    public static final int DEFAULT_USER_AGENT_MAX_LENGTH = 2048;
    private int userAgentMaxLength = DEFAULT_USER_AGENT_MAX_LENGTH;

    private AnalyzerConfig config;

    private boolean delayInitialization;

    @NonNull
    @Override
    public AnalyzerConfig getConfig() {
        return config;
    }

    void addAnalyzerConfig(AnalyzerConfig analyzerConfig) {
        if (config == null) {
            config = analyzerConfig;
        } else {
            config.merge(analyzerConfig);
        }
    }

    @SuppressWarnings("unused") // Private constructor for serialization systems ONLY (like Kryo)
    private UserAgentStringMatchMaker() {
        initTransientFields();
    }

    /*
     * Initialize the transient default values
     */
    void initTransientFields() {
        // All runtime structures are (re)created (just in time) by initializeMatchers()
        runtimeIsReady = false;
        parseStatePool = null;
        parseStatePoolSlots = 0;
        keyValuePaths = Collections.emptySet();
    }

    private void readObject(java.io.ObjectInputStream stream)
        throws IOException, ClassNotFoundException {
        initTransientFields();
        stream.defaultReadObject();
        showDeserializationStats();
    }

    /**
     * This is used to configure the provided Kryo instance if Kryo serialization is desired.
     * The expected type here is Object because otherwise the Kryo library becomes
     * a mandatory dependency on any project that uses Yauaa.
     * @param kryo The instance of com.esotericsoftware.kryo.Kryo that needs to be configured.
     */
    public static void configureKryo(Object kryo) {
        KryoConfig.configureKryo((Kryo) kryo);
    }

    public static class KryoSerializer extends FieldSerializer<UserAgentStringMatchMaker> {
        public KryoSerializer(Kryo kryo, Class<?> type) {
            super(kryo, type);
        }

        @Override
        public void write(Kryo kryo, Output output, UserAgentStringMatchMaker object) {
            // Get rid of needless data
            object.reset();
            super.write(kryo, output, object);
        }

        @Override
        public UserAgentStringMatchMaker read(Kryo kryo, Input input, Class<? extends UserAgentStringMatchMaker> type) {
            UserAgentStringMatchMaker uaa = super.read(kryo, input, type);
            uaa.initTransientFields();
            uaa.showDeserializationStats();
            return uaa;
        }
    }

    private void showDeserializationStats() {
        List<String> lines = new ArrayList<>();
        lines.add("This Analyzer instance was deserialized.");
        lines.add("");
        lines.add("Lookups      : " + getLookups().size());
        lines.add("LookupSets   : " + getLookupSets().size());
        lines.add("Matchers     : " + allMatchers.size());
        lines.add("Hashmap size : " + informMatcherActions.size());
        lines.add("Ranges map   : " + informMatcherActionRanges.size());
        lines.add("Testcases    : " + getTestCases().size());

        logVersion(lines);
    }

    // --------------------------------------------

    /**
     * In some cases it was found that simply dereferencing the instance and letting the GC clean it all up was "too hard".
     * To assist in these kinds of problem cases this method will wipe the internal data structures as much as possible.
     * After calling this method this instance becomes unusable and cannot be 'repaired'.
     * Normal applications will never need this. Simply dereferencing the analyzer will clean everything,
     * no memory leaks (that we know of).
     */
    public synchronized void destroy() {
        allMatchers.forEach(Matcher::destroy);
        allMatchers.clear();
        allMatchers.trimToSize();

        zeroInputMatchers.forEach(Matcher::destroy);
        zeroInputMatchers.clear();
        zeroInputMatchers.trimToSize();

        informMatcherActions.clear();

        config = null;

        if (wantedFieldNames != null) {
            wantedFieldNames.clear();
        }

        flattener.clear();

        invalidateCaches();

        parseStatePool = null;
        parseStatePoolSlots = 0;
        keyValuePaths = Collections.emptySet();
    }

    private void invalidateCaches(){
        allPossibleFieldNamesCache=null;
        allPossibleFieldNamesSortedCache=null;
    }

    // --------------------------------------------

    public void loadResources(String resourceString) {
        loadResources(resourceString, showMatcherStats, false);
    }

    public void loadResources(String resourceString, boolean showLoadMessages, boolean optionalResources) {
        if (matchersHaveBeenInitialized) {
            throw new IllegalStateException("Refusing to load additional resources after the datastructures have been initialized.");
        }

        AnalyzerConfig extraConfig = new ConfigLoader(showLoadMessages)
            .addResource(resourceString, optionalResources)
            .load();

        addAnalyzerConfig(extraConfig);
        invalidateCaches();
        // FIXME: When a second set of resources are loaded then all already existing
        //        matchers are created again.
        //        So this can be rebuild of what has already been done. #SLOW!
        //        The only impact is at startup a few 100ms slower one-off.
        finalizeLoadingRules();
    }

    public void finalizeLoadingRules() {
        flattener = new UserAgentTreeFlattener(this);

        if (wantedFieldNames != null) {
            int wantedSize = wantedFieldNames.size();
            if (wantedFieldNames.contains(SET_ALL_FIELDS)) {
                wantedSize--;
            }
            LOG.info("Building all needed matchers for the requested {} fields.", wantedSize);
        } else {
            LOG.info("Building all matchers for all possible fields.");
        }

        Map<String, MatcherConfig> matcherConfigs = config.getMatcherConfigs();
        userAgentMaxLength = config.getUserAgentMaxLength();

        if (matcherConfigs.isEmpty()) {
            throw new InvalidParserConfigurationException("No matchers were loaded at all.");
        }

        allMatchers.clear();
        for (Map.Entry<String, MatcherConfig> matcherConfigEntry : matcherConfigs.entrySet()) {
            MatcherConfig matcherConfig = matcherConfigEntry.getValue();
            try {
                allMatchers.add(new Matcher(this, wantedFieldNames, matcherConfig));
            } catch (UselessMatcherException ume) {
//                skippedMatchers++;
            }
        }

        verifyWeAreNotAskingForImpossibleFields();
        if (!delayInitialization) {
            initializeMatchers();
        }
    }

    private void verifyWeAreNotAskingForImpossibleFields() {
        if (wantedFieldNames == null) {
            return; // Nothing to check
        }
        List<String> impossibleFields = new ArrayList<>();
        List<String> allPossibleFields = getAllPossibleFieldNamesSorted();

        for (String wantedFieldName: wantedFieldNames) {
            if (isSystemField(wantedFieldName)) {
                continue; // These are fine
            }
            if (!allPossibleFields.contains(wantedFieldName)) {
                impossibleFields.add(wantedFieldName);
            }
        }
        if (impossibleFields.isEmpty()) {
            return;
        }
        throw new InvalidParserConfigurationException("We cannot provide these fields:" + impossibleFields);
    }

    private volatile boolean matchersHaveBeenInitialized = false;

    // ---------------------------------------------------------------------------------------
    // The runtime structures that are derived from the (initialized) matchers.
    // These are transient: After deserialization they are recreated just in time.

    // Set to true (last) when everything is ready to parse.
    private transient volatile boolean runtimeIsReady = false;

    // The total number of actions that can receive input.
    private transient int numberOfActions = 0;

    // The (lowercase) paths for which there is at least one 'path="value"' entry in the informMatcherActions.
    private transient Set<String> keyValuePaths = Collections.emptySet();

    // The pool of reusable ParseState instances.
    // Each slot is on its own cache line(s) to avoid false sharing between threads.
    // Slot n is at index (n + 1) * POOL_SLOT_PADDING: The first cache line(s) of the array also hold the array header.
    // The last slot holds the state of the most recent parse which is ONLY used for debugging/testing (single threaded).
    private transient AtomicReferenceArray<ParseState> parseStatePool = null;
    private transient int parseStatePoolSlots = 0;
    private static final int POOL_SLOT_PADDING = 16;

    /**
     * Initialize all internal datastructures so the analyzer is ready to parse.
     * This is done automatically (just in time) by the first parse.
     * After the initialization this method is very cheap and lock free.
     */
    public void initializeMatchers() {
        if (runtimeIsReady) {
            return;
        }
        initializeMatchersSynchronized();
    }

    private synchronized void initializeMatchersSynchronized() {
        if (runtimeIsReady) {
            return;
        }
        if (!matchersHaveBeenInitialized) {
            LOG.info("Initializing Analyzer data structures");

            if (allMatchers.isEmpty()) {
                throw new InvalidParserConfigurationException("No matchers were loaded at all.");
            }

            long start = System.nanoTime();
            allMatchers.forEach(Matcher::initialize);
            // No more changes: Replace all sets with a compact (array based) version which retains the order.
            informMatcherActions.replaceAll((key, actions) -> new MatcherActionSet(actions));
            long stop = System.nanoTime();

            LOG.info("Built in {} msec : Hashmap {}, Ranges map:{}",
                (stop - start) / 1000000,
                informMatcherActions.size(),
                informMatcherActionRanges.size());

            for (Matcher matcher: allMatchers) {
                if (matcher.getActionsThatRequireInput() == 0) {
                    zeroInputMatchers.add(matcher);
                }
            }
            matchersHaveBeenInitialized = true;
        }

        buildRuntimeStructures();
        runtimeIsReady = true;
    }

    /**
     * Builds all (transient) structures that are needed to do the actual parsing.
     */
    private void buildRuntimeStructures() {
        int actionIndex = 0;
        for (int matcherIndex = 0; matcherIndex < allMatchers.size(); matcherIndex++) {
            actionIndex = allMatchers.get(matcherIndex).initializeRuntime(matcherIndex, actionIndex);
        }
        numberOfActions = actionIndex;

        Set<String> newKeyValuePaths = new HashSet<>();
        for (Map.Entry<String, Set<MatcherAction>> entry : informMatcherActions.entrySet()) {
            for (MatcherAction matcherAction : entry.getValue()) {
                if (matcherAction.getActionIndex() < 0) {
                    throw new IllegalStateException("Found an action that cannot hold any state: " + matcherAction);
                }
            }
            String key = entry.getKey();
            int keyValueSeparator = key.indexOf("=\"");
            if (keyValueSeparator >= 0) {
                newKeyValuePaths.add(key.substring(0, keyValueSeparator));
            }
        }
        keyValuePaths = newKeyValuePaths;

        int poolSlots = Integer.highestOneBit(Math.max(8, Runtime.getRuntime().availableProcessors() * 2) - 1) << 1;
        parseStatePoolSlots = poolSlots;
        parseStatePool = new AtomicReferenceArray<>((poolSlots + 2) * POOL_SLOT_PADDING);
    }

    private static int poolSlotIndex(int slot) {
        return (slot + 1) * POOL_SLOT_PADDING;
    }

    private static int poolDebugSlotIndex(int slots) {
        return (slots + 1) * POOL_SLOT_PADDING;
    }

    @NonNull
    private ParseState newParseState() {
        return new ParseState(this, allMatchers.size(), numberOfActions);
    }

    private static int poolProbe() {
        int hash = System.identityHashCode(Thread.currentThread());
        return hash ^ (hash >>> 16);
    }

    /**
     * Get a ParseState for exclusive use by the current thread.
     * @return A ParseState from the pool (or a new one if the pool is empty).
     */
    @NonNull
    private ParseState acquireParseState() {
        AtomicReferenceArray<ParseState> pool = parseStatePool;
        if (pool != null) {
            int slots = parseStatePoolSlots;
            int start = poolProbe();
            for (int i = 0; i < slots; i++) {
                int index = poolSlotIndex((start + i) & (slots - 1));
                ParseState state = pool.get(index);
                if (state != null && pool.compareAndSet(index, state, null)) {
                    return state;
                }
            }
        }
        return newParseState();
    }

    /**
     * Return a ParseState to the pool for later reuse (by any thread).
     * @param state The state that is no longer used by the current thread.
     */
    private void releaseParseState(@NonNull ParseState state) {
        AtomicReferenceArray<ParseState> pool = parseStatePool;
        if (pool == null) {
            return;
        }
        int slots = parseStatePoolSlots;

        // Retain the most recent state (only for debugging). Avoid needless writes to this shared slot.
        int debugSlotIndex = poolDebugSlotIndex(slots);
        if (pool.get(debugSlotIndex) != state) {
            pool.lazySet(debugSlotIndex, state);
        }

        int start = poolProbe();
        for (int i = 0; i < slots; i++) {
            int index = poolSlotIndex((start + i) & (slots - 1));
            if (pool.get(index) == null && pool.compareAndSet(index, null, state)) {
                return;
            }
        }
        // The pool is full: Simply drop this one.
    }

    /**
     * Only intended for debugging and testing in a single threaded situation.
     * @return The state of the most recent parse (null if not available).
     */
    @Override
    public ParseState getDebugParseState() {
        AtomicReferenceArray<ParseState> pool = parseStatePool;
        if (pool == null) {
            return null;
        }
        return pool.get(poolDebugSlotIndex(parseStatePoolSlots));
    }

    private void setDebugParseState(@Nullable ParseState state) {
        AtomicReferenceArray<ParseState> pool = parseStatePool;
        if (pool != null) {
            pool.set(poolDebugSlotIndex(parseStatePoolSlots), state);
        }
    }

    private transient volatile Set<String> allPossibleFieldNamesCache = null; //NOSONAR: The getter avoids the java:S3077 issues
    public Set<String> getAllPossibleFieldNames() {
        if (allPossibleFieldNamesCache == null) {
            synchronized (this) {
                if (allPossibleFieldNamesCache == null) {
                    Set<String> names = new TreeSet<>(CORE_SYSTEM_GENERATED_FIELDS);
                    if (wantedFieldNames == null) {
                        for (Matcher matcher : allMatchers) {
                            names.addAll(matcher.getAllPossibleFieldNames());
                        }
                        for (FieldCalculator calculator : fieldCalculators) {
                            names.add(calculator.getCalculatedFieldName());
                        }
                    } else {
                        for (Matcher matcher : allMatchers) {
                            // Not all wanted fields are possible !
                            for (String possibleFieldName : matcher.getAllPossibleFieldNames()) {
                                if (wantedFieldNames.contains(possibleFieldName)) {
                                    names.add(possibleFieldName);
                                }
                            }
                        }
                        for (FieldCalculator calculator : fieldCalculators) {
                            String possibleFieldName = calculator.getCalculatedFieldName();
                            if (wantedFieldNames.contains(possibleFieldName)) {
                                names.add(possibleFieldName);
                            }
                        }
                        names.remove(SET_ALL_FIELDS);
                    }
                    allPossibleFieldNamesCache = names;
                }
            }
        }
        return allPossibleFieldNamesCache;
    }

    private transient volatile List<String> allPossibleFieldNamesSortedCache = null; //NOSONAR: The getter avoids the java:S3077 issues
    public List<String> getAllPossibleFieldNamesSorted() {
        if (allPossibleFieldNamesSortedCache == null) {
            synchronized (this) {
                if (allPossibleFieldNamesSortedCache == null) {
                    List<String> fieldNames = new ArrayList<>(getAllPossibleFieldNames());
                    Collections.sort(fieldNames);

                    List<String> names = new ArrayList<>();
                    for (String fieldName : PRE_SORTED_FIELDS_LIST) {
                        fieldNames.remove(fieldName);
                        names.add(fieldName);
                    }
                    names.addAll(fieldNames);

                    // Force the Syntax error to be the last in the list.
                    names.remove(SYNTAX_ERROR);
                    names.add(SYNTAX_ERROR);
                    allPossibleFieldNamesSortedCache = names;
                }
            }
        }
        return allPossibleFieldNamesSortedCache;
    }


    // These are the actual subrange we need for the paths.
    private final Map<String, Set<Range>> informMatcherActionRanges = new HashMap<>(10000);
    @Override
    public void lookingForRange(String treeName, Range range) {
        Set<Range> ranges = informMatcherActionRanges.computeIfAbsent(treeName, k -> new LinkedHashSet<>(4));
        ranges.add(range);
    }

    // We do not want to put ALL lengths in the hashmap for performance reasons
    public static final int MAX_PREFIX_HASH_MATCH = 3;

    // Calculate the max length we will put in the hashmap.
    public static int firstCharactersForPrefixHashLength(String input, int maxChars) {
        return Math.min(maxChars, Math.min(MAX_PREFIX_HASH_MATCH, input.length()));
    }

    public static String firstCharactersForPrefixHash(String input, int maxChars) {
        return input.substring(0, firstCharactersForPrefixHashLength(input, maxChars));
    }

    // These are the paths for which we have prefix requests.
    private final Map<String, Set<Integer>> informMatcherActionPrefixesLengths = new HashMap<>(1000);

    @Override
    public void informMeAboutPrefix(MatcherAction matcherAction, String treeName, String prefix) {
        this.informMeAbout(matcherAction, treeName + "{\"" + firstCharactersForPrefixHash(prefix, MAX_PREFIX_HASH_MATCH) + "\"");
        Set<Integer> lengths = informMatcherActionPrefixesLengths.computeIfAbsent(treeName, k -> new LinkedHashSet<>(4));
        lengths.add(firstCharactersForPrefixHashLength(prefix, MAX_PREFIX_HASH_MATCH));
    }

    @Override
    public Set<Integer> getRequiredPrefixLengths(String treeName) {
        return informMatcherActionPrefixesLengths.get(treeName);
    }

    public void informMeAbout(MatcherAction matcherAction, String keyPattern) {
        String hashKey = keyPattern.toLowerCase(Locale.ROOT);
        Set<MatcherAction> analyzerSet = informMatcherActions
            .computeIfAbsent(hashKey, k -> new LinkedHashSet<>());
        analyzerSet.add(matcherAction);
    }

    private volatile boolean verbose = false;

    public synchronized void setVerbose(boolean newVerbose) {
        this.verbose = newVerbose;
        flattener.setVerbose(newVerbose);
    }

    @Override
    public int getUserAgentMaxLength() {
        return this.userAgentMaxLength;
    }

    private void setAsHacker(MutableUserAgent userAgent, int confidence) {
        userAgent.set(DEVICE_CLASS,                 "Hacker",  confidence);
        userAgent.set(DEVICE_BRAND,                 "Hacker",  confidence);
        userAgent.set(DEVICE_NAME,                  "Hacker",  confidence);
        userAgent.set(DEVICE_VERSION,               "Hacker",  confidence);
        userAgent.set(OPERATING_SYSTEM_CLASS,       "Hacker",  confidence);
        userAgent.set(OPERATING_SYSTEM_NAME,        "Hacker",  confidence);
        userAgent.set(OPERATING_SYSTEM_VERSION,     "Hacker",  confidence);
        userAgent.set(LAYOUT_ENGINE_CLASS,          "Hacker",  confidence);
        userAgent.set(LAYOUT_ENGINE_NAME,           "Hacker",  confidence);
        userAgent.set(LAYOUT_ENGINE_VERSION,        "Hacker",  confidence);
        userAgent.set(LAYOUT_ENGINE_VERSION_MAJOR,  "Hacker",  confidence);
        userAgent.set(AGENT_CLASS,                  "Hacker",  confidence);
        userAgent.set(AGENT_NAME,                   "Hacker",  confidence);
        userAgent.set(AGENT_VERSION,                "Hacker",  confidence);
        userAgent.set(AGENT_VERSION_MAJOR,          "Hacker",  confidence);
        userAgent.set(HACKER_TOOLKIT,               "Unknown", confidence);
        userAgent.set(HACKER_ATTACK_VECTOR,         "Unknown", confidence);
        userAgent.set(REMARKABLE_PATTERN,           "Hacker",  confidence);
    }

    /**
     * Resets the state of the Analyzer to the default state.
     * As all state of a parse is held in a ParseState this only forgets the state of the most recent parse
     * (which is only retained for debugging purposes).
     */
    public synchronized void reset() {
        setDebugParseState(null);
    }

    /**
     * Parses and analyzes the useragent string provided in the MutableUserAgent instance.
     * NOTE: This method is threadsafe and NOT synchronized: many threads can parse at the same time.
     * @param userAgent The MutableUserAgent instance that is to be parsed and that gets all results
     * @return The provided MutableUserAgent with all results.
     */
    @NonNull
    public MutableUserAgent parse(MutableUserAgent userAgent) {
        initializeMatchers();
        String useragentString = userAgent.getUserAgentString();
        if (useragentString != null && useragentString.length() > userAgentMaxLength) {
            setAsHacker(userAgent, 100);
            userAgent.setForced(HACKER_ATTACK_VECTOR, "Buffer overflow", 100);
            return userAgent;
        }

        // NOTE: If an Error (like OutOfMemoryError) occurs the state is not put back in the pool.
        ParseState state = acquireParseState();
        boolean verboseParse = verbose;
        state.startParse(userAgent.isDebug(), verboseParse);

        try {
            userAgent = flattener.parse(userAgent, state);

            inform(state, SYNTAX_ERROR, userAgent.getValue(SYNTAX_ERROR), null);

            if (verboseParse) {
                LOG.info("=========== Checking all Touched Matchers: {}", state.getTouchedMatchersSize());
            }
            // Fire all Analyzers with any input
            for (int i = 0; i < state.getTouchedMatchersSize(); i++) {
                Matcher matcher = state.getTouchedMatcher(i);
                if (matcher != null) {
                    matcher.analyze(state, userAgent);
                }
            }

            if (verboseParse) {
                LOG.info("=========== Checking all Zero Input Matchers: {}", zeroInputMatchers.size());
            }
            // Fire all Analyzers that should not get input
            for (Matcher matcher : zeroInputMatchers) {
                matcher.analyze(state, userAgent);
            }

            userAgent.processSetAll();
        } catch (UserAgentTooDeeplyNestedException tooDeeplyNested) {
            // Parsing this useragent could overflow the stack.
            // Just like a useragent that is too long this is a hacker trying to break the system.
            userAgent.reset();
            setAsHacker(userAgent, 100);
            userAgent.setForced(HACKER_ATTACK_VECTOR, "Stack overflow", 100);
        } catch (RuntimeException rte) {
            // If this occurs then someone has found a previously undetected problem.
            // So this is a safety for something that 'can' but 'should not' occur.
            userAgent.reset();
            setAsHacker(userAgent, 10000);
            userAgent.setForced(HACKER_ATTACK_VECTOR, "Yauaa Exploit", 10000);
        }

        releaseParseState(state);
        return userAgent;
    }

    private static final List<String> CORE_SYSTEM_GENERATED_FIELDS = new ArrayList<>();

    static {
        CORE_SYSTEM_GENERATED_FIELDS.add(SYNTAX_ERROR);
    }

    public boolean isWantedField(String fieldName) {
        if (wantedFieldNames == null) {
            return true;
        }
        return wantedFieldNames.contains(fieldName);
    }

    public Set<String> getWantedFieldNames(){
        return wantedFieldNames;
    }

    private final List<FieldCalculator> fieldCalculators = new ArrayList<>();

    public void setFieldCalculators(List<FieldCalculator> newFieldCalculators) {
        fieldCalculators.addAll(newFieldCalculators);
    }

    public MutableUserAgent hardCodedPostProcessing(MutableUserAgent userAgent) {
        // If it is really really bad ... then it is a Hacker.
        if ("true".equals(userAgent.getValue(SYNTAX_ERROR))) {
            if (userAgent.get(DEVICE_CLASS).getConfidence() == -1) {
                setAsHacker(userAgent, 10);
            }
        }

        // Calculate all fields that are constructed from the found ones.
        boolean verboseParse = verbose;
        for (FieldCalculator fieldCalculator: fieldCalculators) {
            if (verboseParse) {
                LOG.info("Running FieldCalculator: {}", fieldCalculator);
            }
            fieldCalculator.calculate(userAgent);
        }

        return userAgent;
    }

    public Set<Range> getRequiredInformRanges(String treeName) {
        // NOTE: This is called during parsing (by many threads at the same time) so this must be read only.
        return informMatcherActionRanges.getOrDefault(treeName, Collections.emptySet());
    }

    /**
     * Only intended for debugging and testing: Informs the state of the most recent parse.
     * During normal parsing all informs go directly into the ParseState of that parse.
     * @param key The path
     * @param value The value
     * @param ctx The node in the parse tree
     */
    @Override
    public void inform(String key, String value, ParseTree ctx) {
        initializeMatchers();
        ParseState state = getDebugParseState();
        if (state == null) {
            state = newParseState();
            state.startParse(false, verbose);
            setDebugParseState(state);
        }
        inform(state, key, value, ctx);
    }

    /**
     * Find all actions that are interested in this path/value combination and give them the information.
     * This is the hottest code path of the entire analyzer.
     * @param state The state of the current parse
     * @param key The path
     * @param value The value
     * @param ctx The node in the parse tree
     */
    void inform(@NonNull ParseState state, @NonNull String key, @NonNull String value, @Nullable ParseTree ctx) {
        boolean verboseParse = state.isVerbose();
        // The keys are (almost) always already lowercase in which case no new String is created.
        String lowerKey = key.toLowerCase(Locale.ROOT);
        inform(state, verboseParse, key, lowerKey, key, value, ctx);

        // Only create (and look up) the 'key="value"' combination if any rule has such an entry for this key.
        // A path never contains '="' so if the combination exists the lowercase key is in the keyValuePaths.
        if (keyValuePaths.contains(lowerKey)) {
            String match = key + "=\"" + value + '"';
            inform(state, verboseParse, match, match.toLowerCase(Locale.ROOT), key, value, ctx);
        } else {
            if (verboseParse) {
                LOG.info("--- Have (0): {}", key + "=\"" + value + '"');
            }
        }

        Set<Integer> lengths = getRequiredPrefixLengths(key);
        if (lengths != null) {
            int valueLength = value.length();
            for (Integer prefixLength : lengths) {
                if (valueLength >= prefixLength) {
                    String match = key + "{\"" + firstCharactersForPrefixHash(value, prefixLength) + '"';
                    inform(state, verboseParse, match, match.toLowerCase(Locale.ROOT), key, value, ctx);
                }
            }
        }
    }

    private void inform(ParseState state, boolean verboseParse, String match, String lowerMatch, String key, String value, ParseTree ctx) {
        Set<MatcherAction> relevantActions = informMatcherActions.get(lowerMatch);
        if (verboseParse) {
            if (relevantActions == null) {
                LOG.info("--- Have (0): {}", match);
            } else {
                LOG.info("+++ Have ({}): {}", relevantActions.size(), match);

                int count = 1;
                for (MatcherAction action : relevantActions) {
                    LOG.info("+++ -------> ({}): {}", count, action);
                    count++;
                }
            }
        }

        if (relevantActions != null) {
            if (relevantActions instanceof MatcherActionSet) {
                // The normal case: Iterate without creating an Iterator.
                for (MatcherAction matcherAction : ((MatcherActionSet) relevantActions).getActions()) {
                    matcherAction.inform(state, key, value, ctx);
                }
            } else {
                for (MatcherAction matcherAction : relevantActions) {
                    matcherAction.inform(state, key, value, ctx);
                }
            }
        }
    }

    // ===============================================================================================================

    // ===============================================================================================================

    @SuppressWarnings("this-escape")
    public UserAgentStringMatchMaker(
        AnalyzerConfig        analyzerConfig,
        boolean               showMatcherStats,
        boolean               delayInitialization) {

        this.config = analyzerConfig;
        this.showMatcherStats = showMatcherStats;
        this.delayInitialization = delayInitialization;

        Set<String> newWantedFieldNames = this.config.getWantedFieldNames();
        if (newWantedFieldNames != null && !newWantedFieldNames.isEmpty()) {
            this.wantedFieldNames = new TreeSet<>(newWantedFieldNames);
        }

        registerFieldCalculator(new ConcatNONDuplicatedCalculator(AGENT_NAME_VERSION_MAJOR,         AGENT_NAME,             AGENT_VERSION_MAJOR));
        registerFieldCalculator(new ConcatNONDuplicatedCalculator(AGENT_NAME_VERSION,               AGENT_NAME,             AGENT_VERSION));
        registerFieldCalculator(new MajorVersionCalculator(AGENT_VERSION_MAJOR,                     AGENT_VERSION));

        registerFieldCalculator(new ConcatNONDuplicatedCalculator(WEBVIEW_APP_NAME_VERSION_MAJOR,   WEBVIEW_APP_NAME,       WEBVIEW_APP_VERSION_MAJOR));
        registerFieldCalculator(new ConcatNONDuplicatedCalculator(WEBVIEW_APP_NAME_VERSION,         WEBVIEW_APP_NAME,       WEBVIEW_APP_VERSION));
        registerFieldCalculator(new MajorVersionCalculator(WEBVIEW_APP_VERSION_MAJOR,               WEBVIEW_APP_VERSION));

        registerFieldCalculator(new ConcatNONDuplicatedCalculator(LAYOUT_ENGINE_NAME_VERSION_MAJOR, LAYOUT_ENGINE_NAME,     LAYOUT_ENGINE_VERSION_MAJOR));
        registerFieldCalculator(new ConcatNONDuplicatedCalculator(LAYOUT_ENGINE_NAME_VERSION,       LAYOUT_ENGINE_NAME,     LAYOUT_ENGINE_VERSION));
        registerFieldCalculator(new MajorVersionCalculator(LAYOUT_ENGINE_VERSION_MAJOR,             LAYOUT_ENGINE_VERSION));

        // We use this way because of "Windows NT" ,"10.0" --> "Windows 10.0" --> "Windows 10"
        registerFieldCalculator(new MajorVersionCalculator(OPERATING_SYSTEM_NAME_VERSION_MAJOR,     OPERATING_SYSTEM_NAME_VERSION));
        registerFieldCalculator(new MacOSXMajorVersionCalculator(OPERATING_SYSTEM_NAME_VERSION_MAJOR,     OPERATING_SYSTEM_NAME_VERSION));

        registerFieldCalculator(new ConcatNONDuplicatedCalculator(OPERATING_SYSTEM_NAME_VERSION,    OPERATING_SYSTEM_NAME,  OPERATING_SYSTEM_VERSION));
        registerFieldCalculator(new MajorVersionCalculator(OPERATING_SYSTEM_VERSION_MAJOR,          OPERATING_SYSTEM_VERSION));
        registerFieldCalculator(new MacOSXMajorVersionCalculator(OPERATING_SYSTEM_VERSION_MAJOR,     OPERATING_SYSTEM_VERSION));

        registerFieldCalculator(new CalculateAgentClass());
        registerFieldCalculator(new CalculateAgentName());
        registerFieldCalculator(new CalculateNetworkType());
        registerFieldCalculator(new CalculateDeviceName());
        registerFieldCalculator(new CalculateDeviceBrand(this));
        registerFieldCalculator(new CalculateAgentEmail());
        registerFieldCalculator(new VersionCleanupCalculator(AGENT_VERSION));
        registerFieldCalculator(new VersionCleanupCalculator(LAYOUT_ENGINE_VERSION));

        Collections.reverse(fieldCalculators);
        verifyCalculatorDependencyOrdering();

        if (this.wantedFieldNames != null && !this.wantedFieldNames.isEmpty()) {
            this.wantedFieldNames.addAll(extraDependenciesNeededByClientCalculator(this.wantedFieldNames));
            this.wantedFieldNames.addAll(dependenciesNeededByCalculators);

            // We have a tricky dependency between the DeviceBrand and the DeviceName.
            // If either one is wanted then we must always make them both wanted.
            if (wantedFieldNames.contains(DEVICE_BRAND) || wantedFieldNames.contains(DEVICE_NAME)) {
                wantedFieldNames.add(DEVICE_BRAND);
                wantedFieldNames.add(DEVICE_NAME);
            }

        }

        finalizeLoadingRules();
    }

    private final Set<String> allFieldsForWhichACalculatorExists    = new HashSet<>();
    private final Set<String> dependenciesNeededByCalculators       = new HashSet<>();

    private void registerFieldCalculator(FieldCalculator fieldCalculator) {
        String calculatedFieldName = fieldCalculator.getCalculatedFieldName();
        allFieldsForWhichACalculatorExists.add(calculatedFieldName);
        if (isWantedField(calculatedFieldName) ||
            dependenciesNeededByCalculators.contains(calculatedFieldName)) {
            fieldCalculators.add(fieldCalculator);
            dependenciesNeededByCalculators.addAll(fieldCalculator.getDependencies());
        }
    }

    protected void verifyCalculatorDependencyOrdering() {
        // Verify calculator dependencies ordering
        Set<String> seenCalculatedFields = new HashSet<>();
        for (FieldCalculator fieldCalculator: fieldCalculators) {
            for (String dependency: fieldCalculator.getDependencies()){
                if (allFieldsForWhichACalculatorExists.contains(dependency)) {
                    if (!seenCalculatedFields.contains(dependency)) {
                        throw new InvalidParserConfigurationException(
                            "Calculator ordering is wrong:" +
                                "For " + fieldCalculator.getCalculatedFieldName() +
                                " we need " + dependency + " which is a " +
                                "calculated field but it has not yet been calculated.");
                    }
                }
            }
            seenCalculatedFields.add(fieldCalculator.getCalculatedFieldName());
        }
    }

    // ===============================================================================================================

    /**
     * This function is used only for analyzing which patterns that could possibly be relevant
     * were actually relevant for the matcher actions.
     * @return The list of Matches that were possibly relevant.
     */
    public List<MatchesList.Match> getMatches() {
        ParseState state = getDebugParseState();
        List<MatchesList.Match> allMatches = new ArrayList<>(128);
        for (Matcher matcher: getAllMatchers()) {
            allMatches.addAll(matcher.getMatches(state));
        }
        return allMatches;
    }

    public synchronized List<MatchesList.Match> getUsedMatches(MutableUserAgent userAgent) {
        initializeMatchers();
        ParseState state = acquireParseState();
        state.startParse(false, verbose);

        try {
            flattener.parse(userAgent, state);
        } catch (UserAgentTooDeeplyNestedException tooDeeplyNested) {
            // Nothing was parsed so nothing matched.
        }

        List<MatchesList.Match> allMatches = new ArrayList<>(128);
        for (Matcher matcher: getAllMatchers()) {
            allMatches.addAll(matcher.getUsedMatches(state));
        }
        releaseParseState(state);
        return allMatches;
    }

    // ===============================================================================================================

    @Override
    public String toString() {
        return "UserAgentStringMatchMaker{" +
//            "\nallMatchers=" + allMatchers +
//            "\n, zeroInputMatchers=" + zeroInputMatchers +
//            "\n, informMatcherActions=" + informMatcherActions +
            "\n, showMatcherStats=" + showMatcherStats +
//            "\n, doingOnlyASingleTest=" + doingOnlyASingleTest +
            "\n, wantedFieldNames=" + wantedFieldNames +
//            "\n, testCases=" + testCases +
//            "\n, lookups=" + lookups +
//            "\n, lookupSets=" + lookupSets +
//            "\n, flattener=" + flattener +
            "\n, userAgentMaxLength=" + userAgentMaxLength +
            "\n, delayInitialization=" + delayInitialization +
            "\n, matchersHaveBeenInitialized=" + matchersHaveBeenInitialized +
//            "\n, informMatcherActionRanges=" + ToString.toString(informMatcherActionRanges) +
//            "\n, informMatcherActionPrefixesLengths=" + ToString.toString(informMatcherActionPrefixesLengths) +
            "\n, verbose=" + verbose +
//            "\n, fieldCalculators=" + fieldCalculators +
            "\n}";
    }
}
