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
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import org.jspecify.annotations.NonNull;

import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;

/**
 * An immutable, compact (array based) Set of MatcherActions that retains the order of the original Set.
 * <p>
 * After initialization all the (many!) sets in the hashmap of the MatchMaker are replaced by instances of this class.
 * This uses a lot less memory than a LinkedHashSet and iterating over it (which is done for every node of
 * every parsed useragent) does not create any garbage.
 */
@DefaultSerializer(MatcherActionSet.KryoSerializer.class)
public final class MatcherActionSet extends AbstractSet<MatcherAction> implements Serializable {

    /**
     * Without this Kryo would use the CollectionSerializer which only works for mutable collections.
     */
    public static class KryoSerializer extends FieldSerializer<MatcherActionSet> {
        public KryoSerializer(Kryo kryo, Class<?> type) {
            super(kryo, type);
        }
    }

    private static final MatcherAction[] EMPTY = new MatcherAction[0];

    private final MatcherAction[] actions;

    @SuppressWarnings("unused") // Private constructor for serialization systems ONLY (like Kryo)
    private MatcherActionSet() {
        actions = EMPTY;
    }

    MatcherActionSet(@NonNull Collection<MatcherAction> source) {
        actions = source.toArray(EMPTY);
    }

    /**
     * @return The actions in the original order. Do NOT modify this array.
     */
    @NonNull
    MatcherAction[] getActions() {
        return actions;
    }

    @NonNull
    @Override
    public Iterator<MatcherAction> iterator() {
        // The iterator of Arrays.asList does not support remove.
        return Arrays.asList(actions).iterator();
    }

    @Override
    public int size() {
        return actions.length;
    }
}
