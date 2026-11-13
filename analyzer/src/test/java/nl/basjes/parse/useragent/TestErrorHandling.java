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

import nl.basjes.parse.useragent.UserAgent.MutableUserAgent;
import nl.basjes.parse.useragent.analyze.InvalidParserConfigurationException;
import nl.basjes.parse.useragent.debug.UserAgentAnalyzerTester;
import nl.basjes.parse.useragent.parse.EvilManualUseragentStringHacks;
import nl.basjes.parse.useragent.parse.UserAgentTreeFlattener;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.core.AllOf.allOf;
import static org.hamcrest.core.StringContains.containsString;
import static org.hamcrest.core.StringStartsWith.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestErrorHandling {

    private void runTest(String resourceString, Matcher<String> expectedMessage) {
        InvalidParserConfigurationException exception =
            assertThrows(InvalidParserConfigurationException.class, () -> {
                UserAgentAnalyzerTester uaa = UserAgentAnalyzerTester
                    .newBuilder()
                    .dropDefaultResources()
                    .keepTests()
                    .addResources(resourceString)
                    .build();
                assertTrue(uaa.runTests(false, false));
            });

        assertTrue(expectedMessage.matches(exception.getMessage()), "Bad message:" + exception.getMessage());
    }

    @Test
    void checkNoFile() {
        runTest(
            "classpath*:BadDefinitions/ThisOneDoesNotExist---Really.yaml",
            containsString("There were no resources found for the expression: "));
    }

    @Test
    void checkEmptyFile() {
        runTest(
            "classpath*:BadDefinitions/EmptyFile.yaml",
            containsString("No matchers were loaded at all."));
    }

    @Test
    void checkAlmostEmptyFile() {
        runTest(
            "classpath*:BadDefinitions/AlmostEmptyFile.yaml",
            containsString("The value should be a sequence but it is a scalar"));
    }

    @Test
    void checkBadStructure() {
        runTest(
            "classpath*:BadDefinitions/BadStructure.yaml",
            containsString("The top level entry MUST be 'config'."));
    }

    @Test
    void checkFileIsNotAMap() {
        runTest(
            "classpath*:BadDefinitions/FileIsNotAMap.yaml",
            containsString("Yaml config problem.(FileIsNotAMap.yaml:21): The value should be a sequence but it is a mapping"));
    }

    @Test
    void checkLookupSetMissing() {
        runTest(
            "classpath*:BadDefinitions/LookupSetMissing.yaml",
            containsString("Missing lookup/lookupSet"));
    }

    @Test
    void checkBadEntry() {
        runTest(
            "classpath*:BadDefinitions/BadEntry.yaml",
            containsString("Found unexpected config entry:"));
    }

    @Test
    void checkLookupMergeMissing() {
        runTest(
            "classpath*:BadDefinitions/LookupMergeMissing.yaml",
            containsString("Unable to merge lookup"));
    }

    @Test
    void checkLookupMergeSetMissing() {
        runTest(
            "classpath*:BadDefinitions/LookupMergeSetMissing.yaml",
            containsString("Unable to merge set"));
    }

    @Test
    void checkLookupMissing() {
        runTest(
            "classpath*:BadDefinitions/LookupMissing.yaml",
            containsString("Missing lookup"));
    }

    @Test
    void checkLookupPrefixMissing() {
        runTest(
            "classpath*:BadDefinitions/LookupPrefixMissing.yaml",
            containsString("Missing lookup"));
    }

    @Test
    void checkIsInLookupPrefixMissing() {
        runTest(
            "classpath*:BadDefinitions/IsInLookupPrefixMissing.yaml",
            containsString("Missing lookup"));
    }

    @Test
    void checkIsNotInLookupPrefixMissing() {
        runTest(
            "classpath*:BadDefinitions/IsNotInLookupPrefixMissing.yaml",
            containsString("Missing lookup"));
    }

    @Test
    void checkLookupDuplicateKey() {
        runTest(
            "classpath*:BadDefinitions/LookupDuplicateKey.yaml",
            containsString("appears multiple times"));
    }

    @Test
    void checkLookupDuplicateKeyMerge() {
        runTest(
            "classpath*:BadDefinitions/LookupDuplicateKey-merge.yaml",
            containsString("multiple different values"));
    }

    @Test void checkFixedStringFailIfFound() {
        runTest(
            "classpath*:BadDefinitions/FixedStringFailIfFound.yaml",
            containsString("It is useless to put a fixed value \"One\" in the failIfFound section."));
    }

    private static final String FIXED_LOOKUP_ERROR = "A lookup for a fixed input value is a needless complexity.";

    @Test void checkFixedStringLookup() {
        runTest(
            "classpath*:BadDefinitions/FixedStringLookup.yaml",
            containsString(FIXED_LOOKUP_ERROR));
    }

    @Test void checkFixedStringLookupContains() {
        runTest(
            "classpath*:BadDefinitions/FixedStringLookupContains.yaml",
            containsString(FIXED_LOOKUP_ERROR));
    }

    @Test void checkFixedStringLookupPrefix() {
        runTest(
            "classpath*:BadDefinitions/FixedStringLookupPrefix.yaml",
            containsString(FIXED_LOOKUP_ERROR));
    }

    @Test void checkFixedStringIsInLookup() {
        runTest(
            "classpath*:BadDefinitions/FixedStringIsInLookup.yaml",
            containsString(FIXED_LOOKUP_ERROR));
    }

    @Test void checkFixedStringIsInLookupContains() {
        runTest(
            "classpath*:BadDefinitions/FixedStringIsInLookupContains.yaml",
            containsString(FIXED_LOOKUP_ERROR));
    }


    @Test void checkFixedStringIsNotInLookupContains() {
        runTest(
            "classpath*:BadDefinitions/FixedStringIsNotInLookupContains.yaml",
            containsString(FIXED_LOOKUP_ERROR));
    }

    @Test void checkFixedStringIsInLookupPrefix() {
        runTest(
            "classpath*:BadDefinitions/FixedStringIsInLookupPrefix.yaml",
            containsString(FIXED_LOOKUP_ERROR));
    }


    @Test void checkFixedStringIsNotInLookupPrefix() {
        runTest(
            "classpath*:BadDefinitions/FixedStringIsNotInLookupPrefix.yaml",
            containsString(FIXED_LOOKUP_ERROR));
    }

    @Test
    void checkNoExtract() {
        runTest(
            "classpath*:BadDefinitions/NoExtract.yaml",
            containsString("Matcher does not extract anything"));
    }

    @Test
    void checkInvalidExtract() {
        runTest(
            "classpath*:BadDefinitions/InvalidExtract.yaml",
            containsString("Invalid extract config line: agent.text=\"foo\""));
    }

    @Test
    void checkNoTestInput() {
        runTest(
            "classpath*:BadDefinitions/NoTestInput.yaml",
            containsString("Test is missing input"));
    }

    @Test
    void checkSyntaxErrorRequire() {
        runTest(
            "classpath*:BadDefinitions/SyntaxErrorRequire.yaml",
            startsWith("Syntax error"));
    }

    @Test
    void checkSyntaxErrorExtract1() {
        runTest(
            "classpath*:BadDefinitions/SyntaxErrorExtract1.yaml",
            startsWith("Syntax error"));
    }

    @Test
    void checkSyntaxErrorExtract2() {
        runTest(
            "classpath*:BadDefinitions/SyntaxErrorExtract2.yaml",
            startsWith("Invalid extract config line"));
    }

    @Test
    void checkSyntaxErrorVariable1() {
        runTest(
            "classpath*:BadDefinitions/SyntaxErrorVariable1.yaml",
            startsWith("Syntax error"));
    }

    @Test
    void checkSyntaxErrorVariable2() {
        runTest(
            "classpath*:BadDefinitions/SyntaxErrorVariable2.yaml",
            startsWith("Invalid variable config line:"));
    }

    @Test
    void checkSyntaxErrorVariableBackReference() {
        runTest(
            "classpath*:BadDefinitions/Variable-BackReference.yaml",
            startsWith("Syntax error"));
    }

    @Test
    void checkSyntaxErrorVariableBadDefinition() {
        runTest(
            "classpath*:BadDefinitions/Variable-BadDefinition.yaml",
            startsWith("Invalid variable config line:"));
    }

    @Test
    void checkSyntaxErrorVariableFixedString() {
        runTest(
            "classpath*:BadDefinitions/Variable-FixedString.yaml",
            startsWith("Syntax error"));
    }

    @Test
    void checkForVariableExistance() {
        runTest(
            "classpath*:BadDefinitions/Variable-NoSuchVariable.yaml",
            startsWith("Syntax error"));
    }

    @Test
    void checkForRecursiveMergeLookup() {
        runTest(
            "classpath*:BadDefinitions/MergeLookup.yaml",
            allOf(
                containsString("Unable to merge lookup "),
                containsString("because it is a recursive merge.")));
    }

    @Test
    void checkForRecursiveMergeLookupIntoSet() {
        runTest(
            "classpath*:BadDefinitions/MergeLookupIntoSet.yaml",
            allOf(
                containsString("Unable to merge lookupSET "),
                containsString("because it is a recursive merge.")));
    }

    @Test
    void checkForRecursiveMergeSet() {
        runTest(
            "classpath*:BadDefinitions/MergeSet.yaml",
            allOf(
                containsString("Unable to merge lookupSET "),
                containsString("because it is a recursive merge.")));
    }

    @Test
    void methodInputValidation(){
        UserAgentAnalyzer uaa = UserAgentAnalyzer.newBuilder()
            .withField("AgentClass")
            .build();

        UserAgent agent = uaa.parse((String)null);
        assertNotNull(agent);
        assertNull(agent.getUserAgentString());

        agent = uaa.parse((MutableUserAgent) null);
        assertNotNull(agent);

        assertNull(EvilManualUseragentStringHacks.fixIt(null));

        MutableUserAgent mutableUserAgent = new MutableUserAgent();
        mutableUserAgent.setUserAgentString(null);

        assertNull(EvilManualUseragentStringHacks.fixIt(mutableUserAgent));
    }

    @Test
    void blankUseragentIsNotAnExploit() {
        // The cleanup reduces these to an empty string which used to cause a StringIndexOutOfBoundsException
        // in EvilManualUseragentStringHacks.fixIt which made the analyzer report these as a "Yauaa Exploit".
        String[] blankUserAgents = {" ", "  ", " ", "()", " ()", "''"};
        for (String blankUserAgent : blankUserAgents) {
            assertEquals("", EvilManualUseragentStringHacks.fixIt(new MutableUserAgent(blankUserAgent)));
        }

        UserAgentAnalyzer uaa = UserAgentAnalyzer
            .newBuilder()
            .withoutCache()
            .hideMatcherLoadStats()
            .build();

        // These must get the same result as the empty useragent.
        UserAgent emptyUserAgent = uaa.parse("");
        for (String blankUserAgent : blankUserAgents) {
            UserAgent userAgent = uaa.parse(blankUserAgent);
            String message = "Bad result for \"" + blankUserAgent + "\"";
            assertNotEquals("Yauaa Exploit", userAgent.getValue("HackerAttackVector"), message);
            assertEquals(emptyUserAgent.getAvailableFieldNamesSorted(), userAgent.getAvailableFieldNamesSorted(), message);
            for (String fieldName : emptyUserAgent.getAvailableFieldNamesSorted()) {
                assertEquals(emptyUserAgent.get(fieldName), userAgent.get(fieldName), message + " in field " + fieldName);
            }
        }
    }

    @Test
    void pathologicalUseragentsAreParsedQuickly() {
        UserAgentAnalyzer uaa = UserAgentAnalyzer
            .newBuilder()
            .withoutCache()
            .hideMatcherLoadStats()
            .withField("DeviceClass")
            .build();

        // A prefix followed by a repeated pattern up to the default maximum length (2048).
        // Without a limit on the full context lookahead of the parser each of these took 4 to 15 seconds of CPU time.
        // Now they take less than 0.2 seconds.
        String[][] prefixAndPatterns = {
            {"invalid../../../../../../../../../../etc/passwd", "/."},
            {"", "//./."},
            {"", "aa/a/"},
            {"", "a-a/"},
            {"", "/.--"},
        };
        for (String[] prefixAndPattern : prefixAndPatterns) {
            StringBuilder useragent = new StringBuilder(prefixAndPattern[0]);
            while (useragent.length() < 2048) {
                useragent.append(prefixAndPattern[1]);
            }
            useragent.setLength(2048);

            long start = System.nanoTime();
            UserAgent userAgent = uaa.parse(useragent.toString());
            long milliseconds = (System.nanoTime() - start) / 1_000_000;

            assertNotNull(userAgent.getValue("DeviceClass"));
            assertTrue(milliseconds < 5000, "Parsing took " + milliseconds + " ms for the pattern \"" + prefixAndPattern[1] + "\"");
        }
    }

    private static UserAgentAnalyzer hackerDetector() {
        return UserAgentAnalyzer
            .newBuilder()
            .withoutCache()
            .hideMatcherLoadStats()
            .withField("DeviceClass")
            .withField("HackerAttackVector")
            .build();
    }

    @Test
    void deeplyNestedUseragentsAreHackers() {
        UserAgentAnalyzer uaa = hackerDetector();

        // A prefix, something that is repeated (each adds 1 level of nesting) and what closes it.
        String[][] prefixOpenClose = {
            {"Mozilla/5.0 ", "(",       ""},
            {"Mozilla/5.0 ", "[",       ""},
            {"Mozilla/5.0 ", "(a; ",    ""},
            {"x/1 ",         "a/1 (",   ""},
            {"x/1 ",         "1./1[",   ""},
            {"x/1 ",         "[@] ",    ""},  // The '@]' is a single token: so the ']' does not close the '['
            {"x/1 ",         "[)](",    ""},  // The ')' does not close the '[' (and the ']' does)
            {"x/1 ",         "(",       ")"},
            {"x/1 ",         "{a}(",    ")"},
        };
        int maxDepth = UserAgentTreeFlattener.MAX_NESTING_DEPTH;
        for (String[] pattern : prefixOpenClose) {
            String allowed = pattern[0] + pattern[1].repeat(maxDepth) + "x" + pattern[2].repeat(maxDepth);
            UserAgent userAgent = uaa.parse(allowed);
            assertNotEquals("Stack overflow", userAgent.getValue("HackerAttackVector"), "Wrong for: " + allowed);

            String tooDeep = pattern[0] + pattern[1].repeat(maxDepth + 1) + "x" + pattern[2].repeat(maxDepth + 1);
            userAgent = uaa.parse(tooDeep);
            assertEquals("Hacker",         userAgent.getValue("DeviceClass"),        "Wrong for: " + tooDeep);
            assertEquals("Stack overflow", userAgent.getValue("HackerAttackVector"), "Wrong for: " + tooDeep);
        }
    }

    private static UserAgent parseWithLessStack(UserAgentAnalyzer uaa, String useragent, int frames) {
        if (frames > 0) {
            return parseWithLessStack(uaa, useragent, frames - 1);
        }
        return uaa.parse(useragent);
    }

    @Test
    void aStackOverflowInTheParserIsCaught() throws InterruptedException {
        UserAgentAnalyzer uaa = hackerDetector();

        // Parse useragents (nested as deep as allowed) with less and less stack until even the analyzer itself fails.
        // Somewhere along the way the parser (which needs by far the most stack) must have run out of stack.
        // Each useragent has a different (random) nesting so the parser cannot reuse anything it learned from earlier ones.
        String[] levels = {"(", "[", "a/1 (", "1./1["};
        Random random = new Random(42);
        AtomicInteger parserRanOutOfStack = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                for (int frames = 0; frames < 1_000_000; frames += 500) {
                    StringBuilder useragent = new StringBuilder("x/1 ");
                    for (int level = 0; level < UserAgentTreeFlattener.MAX_NESTING_DEPTH; level++) {
                        useragent.append(levels[random.nextInt(levels.length)]);
                    }
                    UserAgent userAgent;
                    try {
                        userAgent = parseWithLessStack(uaa, useragent.toString(), frames);
                    } catch (StackOverflowError stackOverflowError) {
                        return; // Too little stack for the analyzer itself
                    }
                    if ("Stack overflow".equals(userAgent.getValue("HackerAttackVector"))) {
                        parserRanOutOfStack.incrementAndGet();
                    }
                }
            } catch (Throwable throwable) {
                unexpected.set(throwable);
            }
        }, "LittleStack", 512 * 1024);
        thread.start();
        thread.join();

        assertNull(unexpected.get());
        assertTrue(parserRanOutOfStack.get() > 0, "The parser never ran out of stack");

        // Afterwards everything must still work normally.
        String phone = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36";
        assertEquals("Phone", uaa.parse(phone).getValue("DeviceClass"));
    }

}
