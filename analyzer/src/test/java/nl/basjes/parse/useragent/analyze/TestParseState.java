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

import nl.basjes.parse.useragent.UserAgent.ImmutableUserAgent;
import nl.basjes.parse.useragent.UserAgent.MutableUserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;
import nl.basjes.parse.useragent.config.TestCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestParseState {

    private ImmutableUserAgent parse(UserAgentStringMatchMaker matchMaker, String userAgent) {
        return new ImmutableUserAgent(matchMaker.parse(new MutableUserAgent(userAgent)));
    }

    @Test
    void reusedParseStateAcrossGenerationWrapAround() {
        UserAgentAnalyzer analyzer = UserAgentAnalyzer
            .newBuilder()
            .withoutCache()
            .delayInitialization()
            .keepTests()
            .hideMatcherLoadStats()
            .build();

        List<String> userAgents = new ArrayList<>();
        for (TestCase testCase : analyzer.getTestCases()) {
            if (testCase.getUserAgent() != null) {
                userAgents.add(testCase.getUserAgent());
            }
            if (userAgents.size() == 250) {
                break;
            }
        }

        // The reference results are created by a separate instance (without any generation wrap around).
        UserAgentStringMatchMaker referenceMatchMaker = new UserAgentStringMatchMaker(analyzer.getConfig(), false, false);
        List<ImmutableUserAgent> expected = new ArrayList<>();
        for (String userAgent : userAgents) {
            expected.add(parse(referenceMatchMaker, userAgent));
        }

        UserAgentStringMatchMaker matchMaker = new UserAgentStringMatchMaker(analyzer.getConfig(), false, false);
        parse(matchMaker, userAgents.get(0));
        ParseState state = matchMaker.getDebugParseState();
        assertNotNull(state);

        // Force the generation counter to wrap around during the next parses.
        state.setGenerationForTesting(Integer.MAX_VALUE - 10);

        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < userAgents.size(); i++) {
                assertEquals(expected.get(i), parse(matchMaker, userAgents.get(i)), "Wrong result for " + userAgents.get(i));
                // In a single threaded situation the same ParseState is used every time.
                assertSame(state, matchMaker.getDebugParseState());
            }
        }
    }
}
