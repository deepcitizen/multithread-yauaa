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

package nl.basjes.parse.useragent;

import com.esotericsoftware.kryo.DefaultSerializer;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import nl.basjes.parse.useragent.UserAgent.ImmutableUserAgent;
import nl.basjes.parse.useragent.UserAgent.MutableUserAgent;
import nl.basjes.parse.useragent.analyze.Matcher;
import nl.basjes.parse.useragent.analyze.MatcherList;
import nl.basjes.parse.useragent.analyze.MatchesList;
import nl.basjes.parse.useragent.analyze.UserAgentStringMatchMaker;
import nl.basjes.parse.useragent.clienthints.ClientHints;
import nl.basjes.parse.useragent.clienthints.ClientHintsAnalyzer;
import nl.basjes.parse.useragent.clienthints.parsers.ParseSecChUa;
import nl.basjes.parse.useragent.clienthints.parsers.ParseSecChUaArch;
import nl.basjes.parse.useragent.clienthints.parsers.ParseSecChUaBitness;
import nl.basjes.parse.useragent.clienthints.parsers.ParseSecChUaFormFactors;
import nl.basjes.parse.useragent.clienthints.parsers.ParseSecChUaFullVersion;
import nl.basjes.parse.useragent.clienthints.parsers.ParseSecChUaFullVersionList;
import nl.basjes.parse.useragent.clienthints.parsers.ParseSecChUaMobile;
import nl.basjes.parse.useragent.clienthints.parsers.ParseSecChUaModel;
import nl.basjes.parse.useragent.clienthints.parsers.ParseSecChUaPlatform;
import nl.basjes.parse.useragent.clienthints.parsers.ParseSecChUaPlatformVersion;
import nl.basjes.parse.useragent.clienthints.parsers.ParseSecChUaWoW64;
import nl.basjes.parse.useragent.config.AnalyzerConfig;
import nl.basjes.parse.useragent.config.AnalyzerConfigHolder;
import nl.basjes.parse.useragent.config.ConfigLoader;
import nl.basjes.parse.useragent.config.TestCase;
import nl.basjes.parse.useragent.utils.KryoConfig;
import nl.basjes.parse.useragent.utils.springframework.util.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static nl.basjes.parse.useragent.UserAgent.DEVICE_CLASS;
import static nl.basjes.parse.useragent.UserAgent.SET_ALL_FIELDS;
import static nl.basjes.parse.useragent.UserAgent.USERAGENT_HEADER;
import static nl.basjes.parse.useragent.UserAgent.USERAGENT_HEADER_FIELDNAME;
import static nl.basjes.parse.useragent.UserAgent.USERAGENT_HEADER_SPEC;
import static nl.basjes.parse.useragent.UserAgent.USERAGENT_HEADER_SPEC_URL;
import static nl.basjes.parse.useragent.config.ConfigLoader.DEFAULT_RESOURCES;
import static nl.basjes.parse.useragent.utils.YauaaVersion.logVersion;
@DefaultSerializer(AbstractUserAgentAnalyzerDirect.KryoSerializer.class)
public abstract class AbstractUserAgentAnalyzerDirect implements Analyzer, AnalyzerConfigHolder, AnalyzerPreHeater, Serializable {

    protected ClientHintsAnalyzer clientHintsAnalyzer = new ClientHintsAnalyzer();
    private UserAgentStringMatchMaker matchMaker;

    private AnalyzerConfig        analyzerConfig;

    // The handlers that are called before and after the built-in analysis (null if there are none).
    // These are only set while building the analyzer and the arrays are never changed.
    private UserAgentHandler @Nullable [] handlersBeforeAnalysis = null;
    private UserAgentHandler @Nullable [] handlersAfterAnalysis  = null;

    @NonNull
    @Override
    public AnalyzerConfig getConfig() {
        return analyzerConfig;
    }

    @Override
    public List<TestCase> getPreheatTestCases() {
        return getTestCases();
    }

    protected UserAgentStringMatchMaker getMatchMaker() {
        return matchMaker;
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

    public static class KryoSerializer extends FieldSerializer<AbstractUserAgentAnalyzerDirect> {
        public KryoSerializer(Kryo kryo, Class<?> type) {
            super(kryo, type);
        }

        @Override
        public void write(Kryo kryo, Output output, AbstractUserAgentAnalyzerDirect object) {
            // Get rid of needless data
            object.reset();
            super.write(kryo, output, object);
        }

        @Override
        public AbstractUserAgentAnalyzerDirect read(Kryo kryo, Input input, Class<? extends AbstractUserAgentAnalyzerDirect> type) {
            return super.read(kryo, input, type);
        }
    }

    protected AbstractUserAgentAnalyzerDirect() {
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
        matchMaker.destroy();
    }

    // --------------------------------------------

    public void loadResources(String resourceString) {
        matchMaker.loadResources(resourceString);
    }

    public void loadResources(String resourceString, boolean showLoadMessages, boolean optionalResources) {
        matchMaker.loadResources(resourceString, showLoadMessages, optionalResources);
    }

    public void initializeMatchers() {
        matchMaker.initializeMatchers();
    }

    public Set<String> getAllPossibleFieldNames() {
        return matchMaker.getAllPossibleFieldNames();
    }

    public List<String> getAllPossibleFieldNamesSorted() {
        return matchMaker.getAllPossibleFieldNamesSorted();
    }

    public synchronized void setVerbose(boolean newVerbose) {
        matchMaker.setVerbose(newVerbose);
    }

    /**
     * Resets the state of the Analyzer to the default state.
     */
    public synchronized void reset() {
        matchMaker.reset();
    }

    /**
     * Parses and analyzes the provided useragent string
     * @param userAgentString The User-Agent String that is to be parsed and analyzed
     * @return An ImmutableUserAgent record that holds all the results.
     */
    @NonNull
    @Override
    public ImmutableUserAgent parse(String userAgentString) {
        return parse(Collections.singletonMap(USERAGENT_HEADER, userAgentString));
    }

    /**
     * Parses and analyzes the provided useragent string
     * @param requestHeaders A map of all useful request Headers: the "User-Agent" and all Client Hints: "Sec-Ch-Ua"*
     * @return An ImmutableUserAgent record that holds all the results.
     */
    @NonNull
    @Override
    public ImmutableUserAgent parse(Map<String, String> requestHeaders) {
        MutableUserAgent userAgent = new MutableUserAgent(getWantedFieldNames());
        userAgent.addHeader(requestHeaders);
        return parse(userAgent);
    }

    /**
     * Parses and analyzes the useragent string provided in the MutableUserAgent instance.
     * NOTE: This method is threadsafe and NOT synchronized: many threads can parse at the same time.
     * NOTE: Always use the returned result: the provided instance does not get the results if the request
     *       is handled by a {@link UserAgentHandler} before the analysis (or comes from a cache).
     * NOTE: The meta of the provided instance (see {@link UserAgent#getMeta()}) is given to the handlers and is in the
     *       returned result (it is never cached).
     * @param inputUserAgent The MutableUserAgent instance that is to be parsed and that gets all results
     * @return An ImmutableUserAgent copy of the results that is suitable for further usage and caching.
     */
    @NonNull
    public ImmutableUserAgent parse(@Nullable MutableUserAgent inputUserAgent) {
        Map<String, String> requestMeta = metaOf(inputUserAgent);
        UserAgentHandler.Request request = newRequestBeforeAnalysis(inputUserAgent, requestMeta);
        if (request != null) {
            ImmutableUserAgent handled = handleBeforeAnalysis(request);
            if (handled != null) {
                return handled;
            }
            requestMeta = request.getCollectedMeta();
        }
        return analyze(inputUserAgent, requestMeta).withMetaIfAbsent(requestMeta);
    }

    /**
     * The built-in analysis followed by the handlers after the analysis.
     * So this does NOT call the handlers before the analysis and does NOT use any cache.
     * The returned result does NOT have the meta of the request (only the meta of the handlers after the analysis)
     * because it can be cached: the caller adds the meta of the request (with withMetaIfAbsent).
     * @param inputUserAgent The MutableUserAgent instance that is to be parsed and that gets all results
     * @param requestMeta The meta of the request (input and handlers before the analysis), null if none.
     * @return An ImmutableUserAgent copy of the results that is suitable for further usage and caching.
     */
    @NonNull
    final ImmutableUserAgent analyze(@Nullable MutableUserAgent inputUserAgent, @Nullable Map<String, String> requestMeta) {
        MutableUserAgent userAgent;
        if (inputUserAgent == null) {
            userAgent = matchMaker.parse(new MutableUserAgent((String) null));
        } else {
            // So we parse the User-Agent header normally
            userAgent = matchMaker.parse(inputUserAgent);

            Map<String, String> requestHeaders = inputUserAgent.getHeaders();
            if (requestHeaders.size() > 1) {
                // Then we check to see what ClientHints are available
                ClientHints clientHints = clientHintsAnalyzer.parse(requestHeaders);

                // Lastly we modify the parsed userAgent with the found clientHints
                userAgent = clientHintsAnalyzer.merge(this, userAgent, clientHints);
            }
            userAgent = matchMaker.hardCodedPostProcessing(userAgent);
        }
        // The analysis itself never has any meta (the input may have meta: that is part of the requestMeta).
        ImmutableUserAgent result = new ImmutableUserAgent(userAgent, null);

        UserAgentHandler[] handlers = handlersAfterAnalysis;
        if (handlers == null) {
            return result;
        }
        UserAgentHandler.Request request = new UserAgentHandler.Request(
            getWantedFieldNames(), result.getUserAgentString(), result.getHeaders(), result, requestMeta);
        MutableUserAgent handled = callHandlers(handlers, request);
        if (handled == null) {
            return result.withMetaIfAbsent(request.getOwnMeta());
        }
        return completeHandlerResult(handled, request, request.getOwnMeta());
    }

    // ===============================================================================================================
    // The handlers

    /**
     * Creates the request for the handlers before the analysis.
     * @param userAgentString The User-Agent of the request.
     * @param requestHeaders All headers of the request (null if the request only has the User-Agent).
     * @param inputMeta The meta of the input of the parse (null if none).
     * @return The new request or null if there are no handlers before the analysis.
     */
    final UserAgentHandler.@Nullable Request newRequestBeforeAnalysis(@Nullable String              userAgentString,
                                                                      @Nullable Map<String, String> requestHeaders,
                                                                      @Nullable Map<String, String> inputMeta) {
        if (handlersBeforeAnalysis == null) {
            return null;
        }
        return new UserAgentHandler.Request(getWantedFieldNames(), userAgentString, requestHeaders, null, inputMeta);
    }

    /**
     * Creates the request for the handlers before the analysis.
     * @param userAgent The input of the parse (null is handled as a request without a User-Agent).
     * @param inputMeta The meta of the input of the parse (null if none).
     * @return The new request or null if there are no handlers before the analysis.
     */
    final UserAgentHandler.@Nullable Request newRequestBeforeAnalysis(@Nullable MutableUserAgent    userAgent,
                                                                      @Nullable Map<String, String> inputMeta) {
        if (handlersBeforeAnalysis == null) {
            return null;
        }
        if (userAgent == null) {
            return newRequestBeforeAnalysis(null, null, inputMeta);
        }
        return newRequestBeforeAnalysis(userAgent.getUserAgentString(), userAgent.getHeaders(), inputMeta);
    }

    /**
     * Calls the handlers before the analysis for a request.
     * @param request The request (from newRequestBeforeAnalysis).
     * @return The (completed) result of the first handler that handled the request or null if none did.
     *         If none did then request.getCollectedMeta() is the meta that the result of the parse must get.
     */
    @Nullable
    final ImmutableUserAgent handleBeforeAnalysis(UserAgentHandler.@NonNull Request request) {
        UserAgentHandler[] handlers = handlersBeforeAnalysis;
        if (handlers == null) {
            return null;
        }
        MutableUserAgent handled = callHandlers(handlers, request);
        if (handled == null) {
            return null;
        }
        return completeHandlerResult(handled, request, request.getCollectedMeta());
    }

    @Nullable
    private static MutableUserAgent callHandlers(@NonNull UserAgentHandler @NonNull [] handlers,
                                                 UserAgentHandler.@NonNull Request request) {
        for (UserAgentHandler handler : handlers) {
            MutableUserAgent result = handler.handle(request);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * @param result The result of a handler.
     * @param request The request that the handler handled.
     * @param requestMeta The meta of the request that the result must get (the meta of the result wins).
     * @return The completed result.
     */
    @NonNull
    private ImmutableUserAgent completeHandlerResult(@NonNull MutableUserAgent result,
                                                     UserAgentHandler.@NonNull Request request,
                                                     @Nullable Map<String, String> requestMeta) {
        // The result is about this request (also if the handler did not create it with request.newResult()).
        request.copyHeadersInto(result);
        // Calculate all fields that are constructed from the provided ones (like AgentNameVersion).
        MutableUserAgent completed = matchMaker.hardCodedPostProcessing(result);
        return new ImmutableUserAgent(completed, UserAgentHandler.Request.combineMeta(requestMeta, completed.getMeta()));
    }

    /**
     * @param userAgent The input of a parse.
     * @return The meta of the input (null if there is none).
     */
    @Nullable
    static Map<String, String> metaOf(@Nullable UserAgent userAgent) {
        if (userAgent == null) {
            return null;
        }
        Map<String, String> meta = userAgent.getMeta();
        if (meta.isEmpty()) {
            return null;
        }
        return meta;
    }

    void setHandlers(@NonNull List<UserAgentHandler> newHandlersBeforeAnalysis,
                     @NonNull List<UserAgentHandler> newHandlersAfterAnalysis) {
        handlersBeforeAnalysis = newHandlersBeforeAnalysis.isEmpty() ? null : newHandlersBeforeAnalysis.toArray(new UserAgentHandler[0]);
        handlersAfterAnalysis  = newHandlersAfterAnalysis.isEmpty()  ? null : newHandlersAfterAnalysis.toArray(new UserAgentHandler[0]);
    }

    // ===============================================================================================================

    public List<String> supportedClientHintHeaders() {
        return clientHintsAnalyzer.supportedClientHintHeaders();
    }

    public boolean isSupportedClientHintHeader(String header) {
        return clientHintsAnalyzer.isSupportedClientHintHeader(header);
    }

    @AllArgsConstructor
    @ToString
    public static class HeaderSpecification {
        @Getter private final String headerName;           // The name of the request Header
        @Getter private final String specificationUrl;     // URL to the specification of the field
        @Getter private final String specificationSummary; // Summary of the specification of the field
        @Getter private final String fieldName;            // A standard name for the field in external interfaces
    }

    private static final Map<String, HeaderSpecification> HEADER_SPECIFICATIONS = new LinkedCaseInsensitiveMap<>();

    private static void addHeaderSpecification(HeaderSpecification headerSpecification) {
        HEADER_SPECIFICATIONS.put(headerSpecification.getHeaderName(), headerSpecification);
    }

    static {
        addHeaderSpecification(
            new HeaderSpecification(
                USERAGENT_HEADER,
                USERAGENT_HEADER_SPEC_URL,
                USERAGENT_HEADER_SPEC,
                USERAGENT_HEADER_FIELDNAME
            ));
        addHeaderSpecification(ParseSecChUa                .getHeaderSpecification());
        addHeaderSpecification(ParseSecChUaArch            .getHeaderSpecification());
        addHeaderSpecification(ParseSecChUaBitness         .getHeaderSpecification());
        addHeaderSpecification(ParseSecChUaFormFactors     .getHeaderSpecification());
        addHeaderSpecification(ParseSecChUaFullVersion     .getHeaderSpecification());
        addHeaderSpecification(ParseSecChUaFullVersionList .getHeaderSpecification());
        addHeaderSpecification(ParseSecChUaMobile          .getHeaderSpecification());
        addHeaderSpecification(ParseSecChUaModel           .getHeaderSpecification());
        addHeaderSpecification(ParseSecChUaPlatform        .getHeaderSpecification());
        addHeaderSpecification(ParseSecChUaPlatformVersion .getHeaderSpecification());
        addHeaderSpecification(ParseSecChUaWoW64           .getHeaderSpecification());
    }

    public Map<String, HeaderSpecification> getAllSupportedHeaders() {
        return HEADER_SPECIFICATIONS;
    }

    public boolean isWantedField(String fieldName) {
        return matchMaker.isWantedField(fieldName);
    }

    public Set<String> getWantedFieldNames(){
        return matchMaker.getWantedFieldNames();
    }

    protected void configure(
        AnalyzerConfig        pAnalyzerConfig,
        boolean               pShowMatcherStats,
        boolean               pDelayInitialization
    ) {
        analyzerConfig = pAnalyzerConfig;
        matchMaker = new UserAgentStringMatchMaker(
            pAnalyzerConfig,
            pShowMatcherStats,
            pDelayInitialization);
    }

    public <T extends AbstractUserAgentAnalyzerDirect> T cloneWithSharedAnalyzerConfig(boolean showMatcherStats, boolean delayInitialization) {
        T newInstance = createNewInstance();
        newInstance.configure(getConfig(), showMatcherStats, delayInitialization);
        // The clone must give the same results so it gets the same handlers (the arrays are never changed).
        AbstractUserAgentAnalyzerDirect clone = newInstance;
        clone.handlersBeforeAnalysis = handlersBeforeAnalysis;
        clone.handlersAfterAnalysis  = handlersAfterAnalysis;
        return newInstance;
    }

    protected abstract <T extends AbstractUserAgentAnalyzerDirect> T createNewInstance();

    // ===============================================================================================================

    /**
     * This function is used only for analyzing which patterns that could possibly be relevant
     * were actually relevant for the matcher actions.
     * @return The list of Matches that were possibly relevant.
     */
    public List<MatchesList.Match> getMatches() {
        return matchMaker.getMatches();
    }

    public synchronized List<MatchesList.Match> getUsedMatches(MutableUserAgent userAgent) {
        return matchMaker.getUsedMatches(userAgent);
    }

    public List<Matcher> getAllMatchers() {
        return matchMaker.getAllMatchers();
    }

    public MatcherList getTouchedMatchers() {
        return matchMaker.getTouchedMatchers();
    }


    // ===============================================================================================================

    @SuppressWarnings("unchecked") // For all the casts of 'this' to 'B'
    public abstract static class AbstractUserAgentAnalyzerDirectBuilder<UAA extends AbstractUserAgentAnalyzerDirect, B extends AbstractUserAgentAnalyzerDirectBuilder<UAA, B>> {
        private final UAA uaa;
        private boolean didBuildStep = false;
        private int preheatIterations = 0;

        private final Set<String>           wantedFieldNames    = new TreeSet<>();  // isEmpty() means "we want everything"
        private final List<String>          resources           = new ArrayList<>();
        private final List<String>          optionalResources   = new ArrayList<>();
        private final List<String>          yamlRules           = new ArrayList<>();
        private       boolean               showMatcherStats    = false;
        private       int                   userAgentMaxLength  = -1; // -1 --> use built in default
        private       boolean               delayInitialization = true;
        private       boolean               keepTests           = true;
        private final List<UserAgentHandler> handlersBeforeAnalysis = new ArrayList<>();
        private final List<UserAgentHandler> handlersAfterAnalysis  = new ArrayList<>();

        protected void failIfAlreadyBuilt() {
            if (didBuildStep) {
                throw new IllegalStateException(
                    "A builder can provide only a single instance. " +
                    "It is not allowed to set values after doing build()");
            }
        }

        protected AbstractUserAgentAnalyzerDirectBuilder(UAA newUaa) {
            this.uaa = newUaa;
            resources.add(DEFAULT_RESOURCES);
        }

        /**
         * Drop the default set of rules. Useful in parsing ONLY company specific useragents.
         * @return the current Builder instance.
         */
        public B dropDefaultResources() {
            failIfAlreadyBuilt();
            resources.remove(DEFAULT_RESOURCES);
            return (B)this;
        }

        /**
         * Add a set of additional rules. Useful in handling specific cases.
         * @param resourceString The resource list that needs to be added.
         * @return the current Builder instance.
         */
        public B addResources(String resourceString) {
            failIfAlreadyBuilt();
            resources.add(resourceString);
            return (B)this;
        }

        /**
         * Add a set of additional rules. Useful in handling specific cases.
         * The startup will continue even if these do not exist.
         * @param resourceString The resource list that should to be added.
         * @return the current Builder instance.
         */
        public B addOptionalResources(String resourceString) {
            failIfAlreadyBuilt();
            optionalResources.add(resourceString);
            return (B)this;
        }

        /**
         * Add a set of additional rules. Useful in handling specific cases.
         * The startup will continue even if these do not exist.
         * @param yamlRule The Yaml expression that should to be added.
         * @return the current Builder instance.
         */
        public B addYamlRule(String yamlRule) {
            failIfAlreadyBuilt();
            yamlRules.add(yamlRule);
            return (B)this;
        }

        /**
         * Use the available testcases to preheat the jvm on this analyzer.
         * @param iterations How many testcases must be run
         * @return the current Builder instance.
         */
        public B preheat(int iterations) {
            failIfAlreadyBuilt();
            this.preheatIterations = iterations;
            return (B)this;
        }

        /**
         * Use the available testcases to preheat the jvm on this analyzer.
         * All available testcases will be run exactly once.
         * @return the current Builder instance.
         */
        public B preheat() {
            failIfAlreadyBuilt();
            this.preheatIterations = -1;
            return (B)this;
        }

        /**
         * Specify an additional field that we want to retrieve.
         * @param fieldName The name of the additional field
         * @return the current Builder instance.
         */
        public B withField(String fieldName) {
            failIfAlreadyBuilt();
            wantedFieldNames.add(fieldName);
            return (B)this;
        }

        /**
         * Specify a set of additional fields that we want to retrieve.
         * @param fieldNames The collection of names of the additional fields
         * @return the current Builder instance.
         */
        public B withFields(Collection<String> fieldNames) {
            for (String fieldName : fieldNames) {
                withField(fieldName);
            }
            return (B)this;
        }

        /**
         * Specify a set of additional fields that we want to retrieve.
         * @param fieldNames The array of names of the additional fields
         * @return the current Builder instance.
         */
        public B withFields(String... fieldNames) {
            for (String fieldName : fieldNames) {
                withField(fieldName);
            }
            return (B)this;
        }

        /**
         * Specify that we simply want to retrieve all possible fields.
         * @return the current Builder instance.
         */
        public B withAllFields() {
            failIfAlreadyBuilt();
            wantedFieldNames.clear();
            return (B)this;
        }

        /**
         * Log additional information during the startup of the analyzer.
         * @return the current Builder instance.
         */
        public B showMatcherLoadStats() {
            failIfAlreadyBuilt();
            showMatcherStats = true;
            return (B)this;
        }

        /**
         * Set the stats logging during the startup of the analyzer back to the default of "minimal".
         * @return the current Builder instance.
         */
        public B hideMatcherLoadStats() {
            failIfAlreadyBuilt();
            showMatcherStats = false;
            return (B)this;
        }

        /**
         * Set maximum length of a useragent for it to be classified as Hacker without any analysis.
         * @param newUserAgentMaxLength The new maximum length of a useragent for it to be classified as Hacker without any analysis.
         * @return the current Builder instance.
         */
        public B withUserAgentMaxLength(int newUserAgentMaxLength) {
            failIfAlreadyBuilt();
            userAgentMaxLength = newUserAgentMaxLength;
            return (B)this;
        }

        /**
         * Retain all testcases in memory after initialization.
         * @return the current Builder instance.
         */
        public B keepTests() {
            failIfAlreadyBuilt();
            keepTests = true;
            return (B)this;
        }

        /**
         * Remove all testcases in memory after initialization.
         * @return the current Builder instance.
         */
        public B dropTests() {
            failIfAlreadyBuilt();
            keepTests = false;
            return (B)this;
        }

        /**
         * Load all patterns and rules but do not yet build the lookup hashMaps yet.
         * For the engine to run these lookup hashMaps are needed so they will be constructed once "just in time".
         * @return the current Builder instance.
         */
        public B delayInitialization() {
            failIfAlreadyBuilt();
            delayInitialization = true;
            return (B)this;
        }

        /**
         * Load all patterns and rules and immediately build the lookup hashMaps.
         * @return the current Builder instance.
         */
        public B immediateInitialization() {
            failIfAlreadyBuilt();
            delayInitialization = false;
            return (B)this;
        }

        /**
         * Add a handler that is called for EVERY parse before anything else is done (so also before the cache).
         * A useragent that it handles is not analyzed by the built-in rules and its result is not cached.
         * See {@link UserAgentHandler} for the details.
         * @param handler The handler. Handlers are called in the order in which they were added.
         * @return the current Builder instance.
         */
        @NonNull
        public B addHandlerBeforeAnalysis(@NonNull UserAgentHandler handler) {
            failIfAlreadyBuilt();
            handlersBeforeAnalysis.add(Objects.requireNonNull(handler, "The handler must not be null"));
            return (B)this;
        }

        /**
         * Add a handler that gets the result of the built-in analysis and that can replace it.
         * It is only called for a useragent that is not in the cache and its result is cached.
         * See {@link UserAgentHandler} for the details.
         * @param handler The handler. Handlers are called in the order in which they were added.
         * @return the current Builder instance.
         */
        @NonNull
        public B addHandlerAfterAnalysis(@NonNull UserAgentHandler handler) {
            failIfAlreadyBuilt();
            handlersAfterAnalysis.add(Objects.requireNonNull(handler, "The handler must not be null"));
            return (B)this;
        }

        boolean showFullVersion = true;
        public B showMinimalVersion() {
            showFullVersion = false;
            return (B)this;
        }

        public B showFullVersion() {
            showFullVersion = true;
            return (B)this;
        }

        /**
         * Construct the analyzer and run the preheat sequence (if requested).
         * @return the new analyzer instance.
         */
        public UAA build() {
            failIfAlreadyBuilt();

            logVersion(showFullVersion);

            // In case we only want specific fields we must add these special cases too
            if (!wantedFieldNames.isEmpty()) {
                // Special field that affects ALL fields.
                wantedFieldNames.add(SET_ALL_FIELDS);

                // This is always needed to determine the Hacker fallback
                wantedFieldNames.add(DEVICE_CLASS);
            }

            boolean keepTestsAfterPreheat = keepTests;
            if (preheatIterations != 0) {
                // Because we want to do a preheat we keep the tests until
                // after the preheat and then drop them if so desired.
                keepTests = true;
            }

            ConfigLoader configLoader = new ConfigLoader(showMatcherStats)
                .keepTests(keepTests)
                .addResource(resources, false)
                .addResource(optionalResources, true);

            int yamlRuleCount = 1;
            for (String yamlRule : yamlRules) {
                configLoader.addYaml(yamlRule, "Manually Inserted Rules " + yamlRuleCount++);
            }

            AnalyzerConfig analyzerConfig =
                configLoader
                    .load()
                    .setUserAgentMaxLength(userAgentMaxLength)
                    .wantedFieldNames(wantedFieldNames);

            uaa.configure(analyzerConfig, showMatcherStats, delayInitialization);
            // Before the preheat so that also warms up the handlers.
            uaa.setHandlers(handlersBeforeAnalysis, handlersAfterAnalysis);

            if (preheatIterations < 0) {
                uaa.preHeat();
            } else {
                if (preheatIterations > 0) {
                    uaa.preHeat(preheatIterations);
                }
            }
            if (!keepTestsAfterPreheat) {
                uaa.dropTests();
            }

            didBuildStep = true;
            return uaa;
        }
    }

    @Override
    public String toString() {
        return "AbstractUserAgentAnalyzerDirect{" +
            "clientHintsAnalyzer=" + clientHintsAnalyzer +
            ", matchMaker=" + matchMaker +
            ", analyzerConfig=" + analyzerConfig +
            '}';
    }
}
