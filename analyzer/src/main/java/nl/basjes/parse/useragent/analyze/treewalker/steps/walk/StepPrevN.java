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

package nl.basjes.parse.useragent.analyze.treewalker.steps.walk;

import nl.basjes.parse.useragent.analyze.treewalker.steps.Step;
import nl.basjes.parse.useragent.analyze.treewalker.steps.WalkList.WalkResult;
import org.antlr.v4.runtime.tree.ParseTree;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class StepPrevN extends Step {

    private final int steps;

    @SuppressWarnings("unused") // Private constructor for serialization systems ONLY (like Kryo)
    private StepPrevN() {
        steps = -1;
    }

    public StepPrevN(int steps) {
        this.steps = steps;
    }

    // NOTE: This step is shared by all threads so it must not have any mutable state.
    // The original version used a (shared) buffer of 20 entries which also failed (ArrayIndexOutOfBoundsException
    // which made the entire useragent a "Yauaa Exploit") if there were 20 or more siblings before the tree.
    private ParseTree prev(ParseTree tree) {
        ParseTree parent = up(tree);

        if (parent == null) {
            return null;
        }

        int childCount = parent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            if (parent.getChild(i) == tree) {
                // Walk back over the preceding siblings (skipping the separators).
                int stepsToDo = steps;
                for (int j = i - 1; j >= 0; j--) {
                    ParseTree child = parent.getChild(j);
                    if (treeIsSeparator(child)) {
                        continue;
                    }
                    stepsToDo--;
                    if (stepsToDo == 0) {
                        return child;
                    }
                }
                return null; // There are not enough previous siblings
            }
        }
        return null; // There is no previous
    }

    @Override
    public WalkResult walk(@NonNull ParseTree tree, @Nullable String value) {
        ParseTree prevTree = prev(tree);
        if (prevTree == null) {
            return null;
        }

        return walkNextStep(prevTree, null);
    }

    @Override
    public String toString() {
        return "Prev(" + steps + ")";
    }

}
