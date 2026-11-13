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

import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;
import nl.basjes.parse.useragent.analyze.WordRangeVisitor.Range;
import nl.basjes.parse.useragent.analyze.treewalker.steps.Step;
import nl.basjes.parse.useragent.analyze.treewalker.steps.WalkList.WalkResult;
import nl.basjes.parse.useragent.analyze.treewalker.steps.compare.StepContains;
import nl.basjes.parse.useragent.analyze.treewalker.steps.compare.StepDefaultIfNull;
import nl.basjes.parse.useragent.analyze.treewalker.steps.compare.StepEndsWith;
import nl.basjes.parse.useragent.analyze.treewalker.steps.compare.StepEquals;
import nl.basjes.parse.useragent.analyze.treewalker.steps.compare.StepIsInSet;
import nl.basjes.parse.useragent.analyze.treewalker.steps.compare.StepIsNotInSet;
import nl.basjes.parse.useragent.analyze.treewalker.steps.compare.StepIsNull;
import nl.basjes.parse.useragent.analyze.treewalker.steps.compare.StepNotEquals;
import nl.basjes.parse.useragent.analyze.treewalker.steps.compare.StepStartsWith;
import nl.basjes.parse.useragent.analyze.treewalker.steps.lookup.StepIsInLookupContains;
import nl.basjes.parse.useragent.analyze.treewalker.steps.lookup.StepIsInLookupPrefix;
import nl.basjes.parse.useragent.analyze.treewalker.steps.lookup.StepIsNotInLookupPrefix;
import nl.basjes.parse.useragent.analyze.treewalker.steps.lookup.StepLookup;
import nl.basjes.parse.useragent.analyze.treewalker.steps.lookup.StepLookupContains;
import nl.basjes.parse.useragent.analyze.treewalker.steps.lookup.StepLookupPrefix;
import nl.basjes.parse.useragent.analyze.treewalker.steps.value.StepBackToFull;
import nl.basjes.parse.useragent.analyze.treewalker.steps.value.StepCleanVersion;
import nl.basjes.parse.useragent.analyze.treewalker.steps.value.StepConcat;
import nl.basjes.parse.useragent.analyze.treewalker.steps.value.StepConcatPostfix;
import nl.basjes.parse.useragent.analyze.treewalker.steps.value.StepConcatPrefix;
import nl.basjes.parse.useragent.analyze.treewalker.steps.value.StepExtractBrandFromUrl;
import nl.basjes.parse.useragent.analyze.treewalker.steps.value.StepIsValidVersion;
import nl.basjes.parse.useragent.analyze.treewalker.steps.value.StepNormalizeBrand;
import nl.basjes.parse.useragent.analyze.treewalker.steps.value.StepReplaceString;
import nl.basjes.parse.useragent.analyze.treewalker.steps.value.StepSegmentRange;
import nl.basjes.parse.useragent.analyze.treewalker.steps.value.StepWordRange;
import nl.basjes.parse.useragent.analyze.treewalker.steps.walk.StepDown;
import nl.basjes.parse.useragent.analyze.treewalker.steps.walk.StepNext;
import nl.basjes.parse.useragent.analyze.treewalker.steps.walk.StepNextN;
import nl.basjes.parse.useragent.analyze.treewalker.steps.walk.StepPrev;
import nl.basjes.parse.useragent.analyze.treewalker.steps.walk.StepPrevN;
import nl.basjes.parse.useragent.analyze.treewalker.steps.walk.StepUp;
import nl.basjes.parse.useragent.parser.UserAgentParser.CommentSeparatorContext;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestSteps {

    private static final Logger LOG = LogManager.getLogger(TestSteps.class);

    private static Map<String, String> lookup;
    private static Set<String>         set;

    private final ParseTree dummyTree = new ParserRuleContext(){
        @Override
        public String getText() {
            return "DuMmY";
        }
    };

    private final ParseTree nullTree = new ParserRuleContext(){
        @Override
        public String getText() {
            return null;
        }
    };

    private final Step returnNullStep = new Step() {
        @Override
        public WalkResult walk(@NonNull ParseTree tree, String value) {
            return null;
        }
    };

    @BeforeAll
    public static void init() {
        lookup = new HashMap<>();
        lookup.put("foo", "FooFoo");
        lookup.put("bar", "BarBar");
        set = lookup.keySet();
    }

    @Test
    void testStepContains() {
        Step step = new StepContains("Foo");
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
        assertNull(step.walk(dummyTree, "Something"));
        assertEquals("SomeFooBar", step.walk(dummyTree, "SomeFooBar").getValue());
    }

    @Test
    void testStepDefaultIfNull() {
        Step step = new StepDefaultIfNull("Foo");
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertEquals("Bar", step.walk(dummyTree, "Bar").getValue());

        step.setNextStep(1, returnNullStep);
        assertEquals("Foo", step.walk(dummyTree, "Bar").getValue());
    }

    @Test
    void testStepDefaultIfNullNoDefault() {
        assertThrows(InvalidParserConfigurationException.class, () ->
            new StepDefaultIfNull(null));
    }

    @Test
    void testStepEndsWith() {
        Step step = new StepEndsWith("Foo");
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
        assertNull(step.walk(dummyTree, "Something"));
        assertEquals("SomethingFoo", step.walk(dummyTree, "SomethingFoo").getValue());
    }

    @Test
    void testStepEquals() {
        Step step = new StepEquals("Foo");
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
        assertNull(step.walk(dummyTree, "Something"));
        assertEquals("Foo", step.walk(dummyTree, "Foo").getValue());
    }

    @Test
    void testStepIsInSet() {
        Step step = new StepIsInSet("MySet", set);
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
        assertNull(step.walk(dummyTree, "Something"));
        assertEquals("Foo", step.walk(dummyTree, "Foo").getValue());
    }

    @Test
    void testStepIsNotInSet() {
        Step step = new StepIsNotInSet("MySet", set);
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertEquals("DuMmY", step.walk(dummyTree, null).getValue());
        assertEquals("Something", step.walk(dummyTree, "Something").getValue());
        assertNull(step.walk(dummyTree, "Foo"));
    }

    @Test
    void testStepIsNull() {
        Step step = new StepIsNull();
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, "Something"));
        assertNull(step.walk(dummyTree, null));
        assertNull(step.walk(nullTree, "Something"));
        assertNotNull(step.walk(nullTree, null));
    }

    @Test
    void testStepNotEquals() {
        Step step = new StepNotEquals("Foo");
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertEquals("DuMmY", step.walk(dummyTree, null).getValue());
        assertNull(step.walk(dummyTree, "Foo"));
        assertEquals("Bar", step.walk(dummyTree, "Bar").getValue());
    }


    @Test
    void testStepStartsWith() {
        Step step = new StepStartsWith("Foo");
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
        assertNull(step.walk(dummyTree, "Something"));
        assertEquals("FooFoo", step.walk(dummyTree, "FooFoo").getValue());
    }


    @Test
    void testStepIsInLookupContains() {
        Step step = new StepIsInLookupContains("Foo", lookup);
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
        assertNull(step.walk(dummyTree, "Something"));
        assertEquals("FooFoo", step.walk(dummyTree, "FooFoo").getValue());
    }

    @Test
    void testStepIsInLookupPrefix() {
        Step step = new StepIsInLookupPrefix("Foo", lookup);
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
        assertNull(step.walk(dummyTree, "Something"));
        assertEquals("FooFoo", step.walk(dummyTree, "FooFoo").getValue());
    }

    @Test
    void testStepIsNotInLookupPrefix() {
        Step step = new StepIsNotInLookupPrefix("Foo", lookup);
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertEquals("DuMmY", step.walk(dummyTree, null).getValue());
        assertEquals("Something", step.walk(dummyTree, "Something").getValue());
        assertNull(step.walk(dummyTree, "FooFoo"));
    }

    @Test
    void testStepLookupContains() {
        Step step = new StepLookupContains("Foo", lookup, "Default");
        assertEquals("Default", step.walk(dummyTree, null).getValue());
        assertEquals("Default", step.walk(dummyTree, "Something").getValue());
        assertEquals("FooFoo", step.walk(dummyTree, "Foo").getValue());
    }

    @Test
    void testStepLookupContainsNoDefault() {
        Step step = new StepLookupContains("Foo", lookup, null);
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
        assertNull(step.walk(dummyTree, "Something"));
        assertEquals("FooFoo", step.walk(dummyTree, "Foo").getValue());
    }

    @Test
    void testStepLookup() {
        Step step = new StepLookup("Foo", lookup, "Default");
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertEquals("Default", step.walk(dummyTree, null).getValue());
        assertEquals("Default", step.walk(dummyTree, "Something").getValue());
        assertEquals("FooFoo", step.walk(dummyTree, "Foo").getValue());
    }

    @Test
    void testStepLookupNoDefault() {
        Step step = new StepLookup("Foo", lookup, null);
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
        assertNull(step.walk(dummyTree, "Something"));
        assertEquals("FooFoo", step.walk(dummyTree, "Foo").getValue());
    }

    @Test
    void testStepLookupPrefix() {
        Step step = new StepLookupPrefix("Foo", lookup, "Default");
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertEquals("Default", step.walk(dummyTree, null).getValue());
        assertEquals("Default", step.walk(dummyTree, "Something").getValue());
        assertEquals("FooFoo", step.walk(dummyTree, "Foo").getValue());
    }

    @Test
    void testStepLookupPrefixNoDefault() {
        Step step = new StepLookupPrefix("Foo", lookup, null);
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
        assertNull(step.walk(dummyTree, "Something"));
        assertEquals("FooFoo", step.walk(dummyTree, "Foo").getValue());
    }

    @Test
    void testStepBackToFull() {
        Step step = new StepBackToFull();
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertEquals("DuMmY", step.walk(dummyTree, null).getValue());
        assertEquals("DuMmY", step.walk(dummyTree, "Something").getValue());
    }

    @Test
    void testStepCleanVersion() {
        Step step = new StepCleanVersion();
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertEquals("DuMmY", step.walk(dummyTree, null).getValue());
        assertEquals("Something", step.walk(dummyTree, "Something").getValue());
        assertEquals("1.2.3", step.walk(dummyTree, "1.2.3").getValue());
        assertEquals("1.2.3", step.walk(dummyTree, "1_2_3").getValue());
    }


    private List<String> validVersions = Arrays.asList(
        "123",
        "123.4",
        "0621100004002",
        "9.9.0.000001",
        "30.0.2254.121224",
        "11.1.7-g",
        "100.0.1185.27",
        "v0.1.4",
        "6.5.122794288.release",
        "6.2.5.53_r2565f18.621",
        "6.0.8.15804AP",
        "6.0a2",
        "6.1.0.66_r1062275.542",
        "3.1-rc5",
        "3.2a1",
        "3.2a1pre",
        "1.10.0-SNAPSHOT",
        "0.8.5-i18n-misc",
        "0.9.7.RELEASE",
        "0.0.0-semantic-release");

    List<String> invalidVersions = Arrays.asList(
        "FOO",
        "1234FOO",
        "43ENDRNMAP1GSITEP"
        );

    @Test
    void testStepIsValidVersion() {
        Step step = new StepIsValidVersion();
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        for (String validVersion : validVersions) {
            WalkResult walkResult = step.walk(dummyTree, validVersion);
            assertNotNull(walkResult,
                "Version string \"" + validVersion + "\" should have been classified as valid (was null).");
            assertEquals(
                validVersion,
                walkResult.getValue(),
                "Version string \"" + validVersion + "\" should have been classified as valid (was different).");
        }

        for (String invalidVersion : invalidVersions) {
            assertNull(
                step.walk(dummyTree, invalidVersion),
                "Version string \"" + invalidVersion + "\" should have been classified as INvalid.");
        }
    }

    @Test
    void testStepReplaceString() {
        Step step = new StepReplaceString("foo", "bar");
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertEquals("DuMmY", step.walk(dummyTree, null).getValue());
        assertEquals("Something", step.walk(dummyTree, "Something").getValue());
        assertEquals("barbar1bar2", step.walk(dummyTree, "foofoo1bar2").getValue());
        assertEquals("1bar2bar3bar4", step.walk(dummyTree, "1foo2bar3foo4").getValue());
    }

    @Test
    void testStepConcat() {
        Step step = new StepConcat(">>", "<<");
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertEquals(">>DuMmY<<", step.walk(dummyTree, null).getValue());
        assertEquals(">>Something<<", step.walk(dummyTree, "Something").getValue());
    }

    @Test
    void testStepConcatPostfix() {
        Step step = new StepConcatPostfix("<<");
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertEquals("DuMmY<<", step.walk(dummyTree, null).getValue());
        assertEquals("Something<<", step.walk(dummyTree, "Something").getValue());
    }

    @Test
    void testStepConcatPrefix() {
        Step step = new StepConcatPrefix(">>");
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertEquals(">>DuMmY", step.walk(dummyTree, null).getValue());
        assertEquals(">>Something", step.walk(dummyTree, "Something").getValue());
    }

    @Test
    void testStepNormalizeBrand() {
        Step step = new StepNormalizeBrand();
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertEquals("Dummy", step.walk(dummyTree, null).getValue());
        assertEquals("Something", step.walk(dummyTree, "something").getValue());
        assertEquals("NielsBasjes", step.walk(dummyTree, "NielsBasjes").getValue());
    }

    @Test
    void testExtractBrandFromUrl() {
        Step step = new StepExtractBrandFromUrl();
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);

        ParseTree tree = new ParserRuleContext(){
            @Override
            public String getText() {
                return "https://yauaa.basjes.nl/";
            }
        };

        assertEquals("Basjes", step.walk(tree, null).getValue());
    }

    @Test
    void testStepSegmentRange() {
        Step step = new StepSegmentRange(new Range(2, 3));
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
        assertEquals("Two|Tree", step.walk(dummyTree, "One|Two|Tree|Four|Five").getValue());
    }

    @Test
    void testStepWordRange() {
        Step step = new StepWordRange(new Range(2, 3));
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
        assertEquals("Two Tree", step.walk(dummyTree, "One Two Tree Four Five").getValue());
    }

    @Test
    void testStepDown() {
        Step step = new StepDown(new NumberRangeList(2, 3), "something");
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
    }

    @Test
    void testStepNext() {
        Step step = new StepNext();
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
    }

    @Test
    void testStepNextN() {
        Step step = new StepNextN(5);
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
    }

    @Test
    void testStepPrev() {
        Step step = new StepPrev();
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
    }

    @Test
    void testStepPrevN() {
        Step step = new StepPrevN(5);
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
    }

    @Test
    void testStepPrevNManySiblings() {
        // A node with many children: the 'real' children are interleaved with both types of separators.
        // An older implementation failed if there were 20 or more (non-separator) siblings before the starting point.
        ParserRuleContext parent = new ParserRuleContext();
        List<ParseTree> nonSeparators = new ArrayList<>();
        for (int i = 0; i < 75; i++) {
            switch (i % 5) {
                case 1:
                    parent.addChild(new TerminalNodeImpl(new CommonToken(Token.MIN_USER_TOKEN_TYPE, ";")));
                    break;
                case 3:
                    parent.addChild(new CommentSeparatorContext(parent, 0));
                    break;
                default:
                    ParserRuleContext child = new ParserRuleContext(parent, 0);
                    parent.addChild(child);
                    nonSeparators.add(child);
            }
        }

        for (int steps = 2; steps <= 4; steps++) {
            Step step = new StepPrevN(steps);
            for (int i = 0; i < nonSeparators.size(); i++) {
                WalkResult result = step.walk(nonSeparators.get(i), null);
                if (i < steps) {
                    assertNull(result, "There should be no result for steps=" + steps + " from child " + i);
                } else {
                    assertNotNull(result, "There should be a result for steps=" + steps + " from child " + i);
                    assertSame(nonSeparators.get(i - steps), result.getTree(),
                        "Wrong result for steps=" + steps + " from child " + i);
                }
            }
        }
    }

    @Test
    void testStepPrevNManySiblingsInAnalyzer() {
        UserAgentAnalyzer uaa = UserAgentAnalyzer
            .newBuilder()
            .dropDefaultResources()
            .addYamlRule(
                "config:\n" +
                "- matcher:\n" +
                "    extract:\n" +
                "      - 'PrevOne  : 1 :agent.(25)product.name=\"Chrome\"^<'\n" +
                "- matcher:\n" +
                "    extract:\n" +
                "      - 'PrevTwo  : 1 :agent.(25)product.name=\"Chrome\"^<<'\n" +
                "- matcher:\n" +
                "    extract:\n" +
                "      - 'PrevFour : 1 :agent.(25)product.name=\"Chrome\"^<<<<'\n")
            .withoutCache()
            .hideMatcherLoadStats()
            .build();

        // The Chrome product is the 25th child of the root node: there are 24 non-separator siblings before it.
        StringBuilder userAgent = new StringBuilder("Mozilla/5.0 (X11) AppleWebKit/537.36 (KHTML, like Gecko)");
        for (int i = 1; i <= 22; i++) {
            userAgent.append(" w").append(i).append(';');
        }
        userAgent.append(" Chrome/120.0.0.0 Safari/537.36");

        // An older implementation failed here which turned the entire result into a "Yauaa Exploit".
        UserAgent result = uaa.parse(userAgent.toString());
        assertEquals("Unknown", result.getValue("HackerAttackVector"));
        assertEquals("w22",     result.getValue("PrevOne"));
        assertEquals("w21",     result.getValue("PrevTwo"));
        assertEquals("w19",     result.getValue("PrevFour"));
    }

    @Test
    void testStepUp() {
        Step step = new StepUp();
        LOG.info("Step {} --> {}", step.getClass().getSimpleName(), step);
        assertNull(step.walk(dummyTree, null));
    }

}
