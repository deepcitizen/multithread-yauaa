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

import nl.basjes.parse.useragent.UserAgent.ImmutableUserAgent;
import nl.basjes.parse.useragent.UserAgent.MutableUserAgent;
import nl.basjes.parse.useragent.utils.springframework.util.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static nl.basjes.parse.useragent.UserAgent.USERAGENT_HEADER;

/**
 * Lets an application analyze (some of the) useragents itself, before or after the built-in analysis.
 * <p>
 * A handler is added with the builder of the analyzer:
 * <ul>
 *     <li>{@code addHandlerBeforeAnalysis}: The handler is called for EVERY parse before anything else is done:
 *     before the cache is used and before the built-in checks (like the maximum length of a useragent).
 *     The result of a handler is NOT cached so a useragent with a random part (like a device id) that is
 *     handled here does not fill the cache with useless entries.
 *     Because it is called for every parse a handler must be fast (certainly for the useragents that it does
 *     not handle) and it must check its input: anyone can send any useragent.</li>
 *     <li>{@code addHandlerAfterAnalysis}: The handler gets the result of the built-in analysis
 *     ({@link Request#getAnalysisResult()}) and can replace it.
 *     It is only called for a useragent that is not in the cache and its result is cached (just like the result
 *     of the built-in analysis) so its result must only depend on the request.</li>
 * </ul>
 * The handlers at the same position are called in the order in which they were added.
 * The first handler that returns a result ends the processing: that result is the answer.
 * A handler that returns {@code null} passes the request on: to the next handler and after the last handler
 * to the built-in analysis (before the analysis) or to the result of the built-in analysis (after the analysis).
 * <p>
 * The returned {@link MutableUserAgent} (preferably created with {@link Request#newResult()}) is completed by
 * the analyzer: the fields that are calculated from other fields (like {@code AgentNameVersion} and
 * {@code AgentVersionMajor}) and that do not have a value yet are calculated and the result gets the headers of
 * the request (so {@link UserAgent#getUserAgentString()} is the useragent that was parsed).
 * So a handler must return a new instance for every request and must not use it after returning it.
 * <p>
 * A handler can also add data of its own to the result: the meta ({@link UserAgent#getMeta()}, key/value strings
 * that are not fields). It adds meta to the result it returns ({@link MutableUserAgent#setMeta(String, String)})
 * or to the request ({@link Request#setMeta(String, String)}), which also works if the handler does not handle the
 * request. The handlers that are called later get the meta so far ({@link Request#getMeta(String)}) and the
 * application gets it from the result of the parse. The meta is always cached together with the result
 * (just like the fields, see {@link MutableUserAgent#setMeta(String, String)}).
 * <p>
 * Many threads use the same handler at the same time so a handler must be threadsafe.
 * An exception thrown by a handler is passed on to the caller of {@code parse}.
 * If the analyzer is serialized the handlers are serialized with it (with Kryo: register the classes of the
 * handlers).
 */
@FunctionalInterface
public interface UserAgentHandler extends Serializable {

    /**
     * Analyzes the request if this handler can do that.
     * @param request The request that must be analyzed.
     * @return The result for this request or {@code null} if this handler does not handle this request.
     */
    @Nullable
    MutableUserAgent handle(@NonNull Request request);

    /**
     * A request that is given to a {@link UserAgentHandler}.
     * The analyzer creates a new instance for every parse.
     */
    final class Request {
        @Nullable private final Collection<String>  wantedFieldNames; // Null means: All fields
        @Nullable private final String              userAgentString;
        @Nullable private final Map<String, String> requestHeaders;   // Null means: Only the User-Agent is known
        @Nullable private final ImmutableUserAgent  analysisResult;   // Null means: There is no analysis (yet)
        @Nullable private       Map<String, String> headers;          // Read only, case-insensitive, made when needed
        @Nullable private final Map<String, String> inheritedMeta;    // The meta from before these handlers (never changed)
        @Nullable private       Map<String, String> ownMeta;          // The meta added by these handlers, made when needed

        Request(@Nullable Collection<String>  wantedFieldNames,
                @Nullable String              userAgentString,
                @Nullable Map<String, String> requestHeaders,
                @Nullable ImmutableUserAgent  analysisResult,
                @Nullable Map<String, String> inheritedMeta) {
            this.wantedFieldNames = wantedFieldNames;
            this.userAgentString  = userAgentString;
            this.requestHeaders   = requestHeaders;
            this.analysisResult   = analysisResult;
            this.inheritedMeta    = inheritedMeta;
        }

        /**
         * @return The useragent that must be analyzed (null if the request does not have a User-Agent).
         */
        @Nullable
        public String getUserAgentString() {
            return userAgentString;
        }

        /**
         * @return All headers of the request (the User-Agent and the Client Hints), read only and the names of the
         * headers are case-insensitive.
         */
        @NonNull
        public Map<String, String> getHeaders() {
            Map<String, String> result = headers;
            if (result == null) {
                Map<String, String> caseInsensitiveHeaders = new LinkedCaseInsensitiveMap<>();
                if (requestHeaders == null) {
                    caseInsensitiveHeaders.put(USERAGENT_HEADER, userAgentString);
                } else {
                    caseInsensitiveHeaders.putAll(requestHeaders);
                }
                result = Collections.unmodifiableMap(caseInsensitiveHeaders);
                headers = result;
            }
            return result;
        }

        /**
         * @return For a handler after the analysis: The result of the built-in analysis (without any meta: the meta so
         * far is {@link #getMeta()}); for a handler before the analysis: null.
         */
        @Nullable
        public ImmutableUserAgent getAnalysisResult() {
            return analysisResult;
        }

        /**
         * The meta of this request so far: the meta of the input of the parse, the meta that the handlers that were
         * called before this one added to the request (also the ones that did not handle it) and for a handler after
         * the analysis also the meta of the handlers before the analysis.
         * @return A read only copy of the meta (never null).
         */
        @NonNull
        public Map<String, String> getMeta() {
            Map<String, String> meta = getCollectedMeta();
            if (meta == null) {
                return Collections.emptyMap();
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(meta));
        }

        /**
         * @param key The key of the meta value.
         * @return The meta value of this key so far (see {@link #getMeta()}) or null if there is none.
         */
        @Nullable
        public String getMeta(@NonNull String key) {
            Map<String, String> meta = ownMeta;
            if (meta != null) {
                String value = meta.get(key);
                if (value != null) {
                    return value;
                }
            }
            meta = inheritedMeta;
            if (meta == null) {
                return null;
            }
            return meta.get(key);
        }

        /**
         * Adds a meta value to the result of this request (it replaces the value that this key had so far).
         * This also works if the handler does not handle the request (returns null): the meta is also given to the
         * next handlers ({@link #getMeta(String)}) and ends up in the result, whoever creates it.
         * The meta of the result that a handler returns wins from the meta that was added to the request and the meta
         * of a handler after the analysis wins from the meta of a handler before the analysis.
         * See {@link MutableUserAgent#setMeta(String, String)} for the details about the caching.
         * @param key The key of the meta value.
         * @param value The meta value.
         */
        public void setMeta(@NonNull String key, @NonNull String value) {
            Objects.requireNonNull(key,   "The key of a meta value must not be null");
            Objects.requireNonNull(value, "A meta value must not be null");
            Map<String, String> meta = ownMeta;
            if (meta == null) {
                meta = new LinkedHashMap<>();
                ownMeta = meta;
            }
            meta.put(key, value);
        }

        /**
         * @return The meta that the handlers added to this request (null if none).
         */
        @Nullable
        Map<String, String> getOwnMeta() {
            return ownMeta;
        }

        /**
         * @return All meta of this request (from before these handlers and from these handlers), null if none.
         * Never change the returned map.
         */
        @Nullable
        Map<String, String> getCollectedMeta() {
            return combineMeta(inheritedMeta, ownMeta);
        }

        /**
         * Combines two metas without changing them.
         * @param lower The meta that loses if both have the same key.
         * @param higher The meta that wins if both have the same key.
         * @return The combination or null if both are empty. If one of them is empty the other one is returned
         *         (so never change the returned map).
         */
        @Nullable
        static Map<String, String> combineMeta(@Nullable Map<String, String> lower, @Nullable Map<String, String> higher) {
            if (lower == null || lower.isEmpty()) {
                if (higher == null || higher.isEmpty()) {
                    return null;
                }
                return higher;
            }
            if (higher == null || higher.isEmpty()) {
                return lower;
            }
            Map<String, String> combined = new LinkedHashMap<>(lower);
            combined.putAll(higher);
            return combined;
        }

        /**
         * Creates an empty result for this request: It has the headers of this request and the same fields as
         * a result of the analyzer (so only the wanted fields if the analyzer was built with a list of fields).
         * Fill it (with {@link MutableUserAgent#set(String, String, long)}) and return it from the handler.
         * A handler after the analysis that only wants to change a few fields can use
         * {@code new MutableUserAgent(request.getAnalysisResult())} instead.
         * The meta of the request ({@link #getMeta()}) does not have to be copied into the result: the analyzer
         * adds it to the returned result (the meta of the returned result wins).
         * @return A new and empty result for this request.
         */
        @NonNull
        public MutableUserAgent newResult() {
            MutableUserAgent result = new MutableUserAgent(wantedFieldNames);
            copyHeadersInto(result);
            return result;
        }

        /**
         * Makes sure that the result has exactly the headers of this request.
         * @param result The result of a handler for this request.
         */
        void copyHeadersInto(@NonNull MutableUserAgent result) {
            Map<String, String> resultHeaders = result.getHeaders();
            if (requestHeaders == null) {
                if (resultHeaders.size() == 1 &&
                    resultHeaders.containsKey(USERAGENT_HEADER) &&
                    Objects.equals(resultHeaders.get(USERAGENT_HEADER), userAgentString)) {
                    return;
                }
                resultHeaders.clear();
                result.addHeader(USERAGENT_HEADER, userAgentString);
                return;
            }
            if (!resultHeaders.equals(requestHeaders)) {
                resultHeaders.clear();
                result.addHeader(requestHeaders);
            }
        }
    }
}
