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

import org.jspecify.annotations.NonNull;

/**
 * Thrown when a useragent is not parsed because its brackets are nested too deeply.
 * Parsing such a useragent needs so much stack that it could fail with a StackOverflowError.
 * <p>
 * The analyzer reports such a useragent as a Hacker.
 */
public final class UserAgentTooDeeplyNestedException extends RuntimeException {

    /**
     * @param message What was too deep.
     */
    public UserAgentTooDeeplyNestedException(@NonNull String message) {
        // Without a stack trace: this is not a bug but a rejected (malicious) input.
        super(message, null, false, false);
    }
}
