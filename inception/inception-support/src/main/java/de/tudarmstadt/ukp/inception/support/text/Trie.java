/*
 * Copyright 2009, Richard Eckart de Castilho
 * Copyright 2012, Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.support.text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;

/**
 * A HashMap-based Trie. Zero-length or null keys are not allowed. Null values are allowed.
 *
 * @param <V>
 *            the value type.
 */
public class Trie<V>
// implements Map<CharSequence, V>
{
    private int size = 0;
    private KeySanitizerFactory sanitizerFactory;

    public class MatchedNode
    {
        public final Node node;
        public final int matchLength;

        public MatchedNode(Trie<V>.Node aNode, int aMatchLength)
        {
            node = aNode;
            matchLength = aMatchLength;
        }
    }

    public class Node
    {
        final Map<Character, Node> children;
        public V value;
        public final int level;
        boolean set;

        Node(final int l)
        {
            children = new TreeMap<Character, Node>();
            level = l;
            set = false;
        }
    }

    private Node root;

    /**
     * Create an empty Trie.
     */
    public Trie()
    {
        clear();
    }

    /**
     * Create an empty Trie.
     * 
     * @param aSanitizer
     *            a key sanitizer to apply when adding entries to the trie
     */
    public Trie(KeySanitizerFactory aSanitizer)
    {
        this();
        sanitizerFactory = aSanitizer;
    }

    /**
     * @see Map#clear()
     */
    public void clear()
    {
        root = new Node(0);
        size = 0;
    }

    /**
     * @param aKey
     *            the key.
     * @param value
     *            the value.
     * @return the old value.
     * @see java.util.Map#put(java.lang.Object, java.lang.Object)
     */
    public V put(final CharSequence aKey, final V value)
    {
        CharSequence key = aKey;

        if (sanitizerFactory != null) {
            key = sanitizerFactory.create().sanitize(key);
        }

        if (key.length() == 0) {
            throw new IllegalArgumentException("Zero-length keys are illegal");
        }

        // if (key.length() == 0) {
        // V oldval = _root.value;
        // _root.value = value;
        // return oldval;
        // }

        Node last = root;
        int level = 1;
        for (int i = 0; i < key.length(); i++) {
            final char k = key.charAt(i);
            Node cur = last.children.get(k);
            if (cur == null) {
                cur = new Node(level);
                last.children.put(k, cur);
            }
            last = cur;
            level++;
        }

        if (!last.set) {
            size++;
        }

        final V oldval = last.value;
        last.value = value;
        last.set = true;
        return oldval;
    }

    /**
     * Try to match the character sequence given in key against the trie starting at the given
     * offset in the key string.
     *
     * @param key
     *            the key.
     * @param offset
     *            the offset.
     * @return the node of the longest matching key.
     */
    public MatchedNode getNode(final CharSequence key, final int offset)
    {
        var matches = getNodes(key, offset);
        return matches.isEmpty() ? null : matches.get(matches.size() - 1);
    }

    /**
     * Try to match the character sequence given in key against the trie starting at the given
     * offset in the key string.
     *
     * @param key
     *            the key.
     * @param offset
     *            the offset.
     * @return the nodes of all matching keys, ordered from the shortest to the longest match.
     */
    public List<MatchedNode> getNodes(final CharSequence key, final int offset)
    {
        // offset outside range
        if (offset > key.length() - 1) {
            return List.of();
        }

        KeySanitizer sanitizer = null;
        if (sanitizerFactory != null) {
            sanitizer = sanitizerFactory.create();
        }

        var matches = new ArrayList<MatchedNode>();
        var last = root;
        for (int i = offset; i < key.length(); i++) {
            char k = key.charAt(i);

            if (sanitizer != null) {
                k = sanitizer.map(k);
                if (k == KeySanitizer.SKIP_CHAR) {
                    if (i == offset) {
                        // The first character must not be a skipped character
                        return List.of();
                    }
                    continue;
                }
            }

            final Node cur = last.children.get(k);
            if (cur == null) {
                break;
            }

            if (cur.set) {
                matches.add(new MatchedNode(cur, i + 1 - offset));
            }
            last = cur;
        }

        return matches;
    }

    /**
     * Try to match the character sequence given in key against the trie. This is the same as
     * calling get(key, 0, key.length()).
     *
     * @param key
     *            the key.
     * @return the node.
     */
    public MatchedNode getNode(final CharSequence key)
    {
        return getNode(key, 0, key.length());
    }

    /**
     * Try to match the character sequence given in key against the trie starting at the given
     * offset in the key string using a specified number of characters.
     *
     * Returns the node even if there is no value set at that point of the Trie!
     *
     * @param key
     *            the key.
     * @param offset
     *            the offset.
     * @param length
     *            the length to match.
     * @return the node.
     */
    private MatchedNode get_node(final CharSequence key, final int offset, final int length)
    {
        // offset or length outside range
        if ((offset > key.length() - 1) || (offset + length > key.length())) {
            return null;
        }

        if (key.length() == 0) {
            return new MatchedNode(root, 0);
        }

        KeySanitizer sanitizer = null;
        if (sanitizerFactory != null) {
            sanitizer = sanitizerFactory.create();
        }
        Node last = root;
        Node match = null;
        int acceptedKeyChars = 0;
        int i = offset;
        for (i = offset; i < offset + length; i++) {
            char k = key.charAt(i);

            if (sanitizer != null) {
                k = sanitizer.map(k);
                if (k == KeySanitizer.SKIP_CHAR) {
                    continue;
                }
            }

            acceptedKeyChars++;

            final Node cur = last.children.get(k);
            if (cur == null) {
                break;
            }
            else {
                match = cur;
            }
            last = cur;
        }

        if (match != null && acceptedKeyChars == match.level) {
            return new MatchedNode(match, i - offset);
        }

        return null;
    }

    /**
     * Try to match the character sequence given in key against the trie starting at the given
     * offset in the key string using a specified number of characters.
     *
     * @param key
     *            the key.
     * @param offset
     *            the offset.
     * @param length
     *            the length.
     * @return the node.
     */
    public MatchedNode getNode(final CharSequence key, final int offset, final int length)
    {
        if (key == null) {
            return null;
        }

        final MatchedNode match = get_node(key, offset, length);
        return ((match != null) && match.node.set) ? match : null;
    }

    public boolean containsKey(final Object key)
    {
        if (!(key instanceof CharSequence)) {
            return false;
        }

        return get(key) != null;
    }

    /**
     * Checks if the given string is a prefix of a key in the Trie.
     *
     * @param prefix
     *            the prefix.
     * @return if the prefix is in the trie.
     */
    public boolean containsPrefix(final CharSequence prefix)
    {
        return containsPrefix(prefix, 0, prefix.length());
    }

    /**
     * Checks if the given character sequence matches against the trie starting at the given offset
     * in the key string using a specified number of characters.
     *
     * @param prefix
     *            the prefix.
     * @param offset
     *            the offset.
     * @param length
     *            the length to match.
     * @return whether the prefix is in the trie.
     */
    public boolean containsPrefix(final CharSequence prefix, final int offset, final int length)
    {
        if (prefix == null) {
            return false;
        }

        final MatchedNode match = get_node(prefix, offset, length);
        return match != null;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.util.Map#get(java.lang.Object)
     */
    public V get(final Object key)
    {
        if (!(key instanceof CharSequence)) {
            return null;
        }

        final MatchedNode n = getNode((CharSequence) key);

        return (n == null) ? null : n.node.value;
    }

    public boolean isEmpty()
    {
        return size == 0;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.util.Map#putAll(java.util.Map)
     */
    public void putAll(final Map<? extends CharSequence, ? extends V> t)
    {
        for (final Map.Entry<? extends CharSequence, ? extends V> e : t.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    public int size()
    {
        return size;
    }

    public Collection<V> values()
    {
        final List<V> vals = new ArrayList<V>(size);
        values(root, vals);
        return vals;
    }

    private void values(final Node cur, final List<V> vals)
    {
        if (cur == null) {
            return;
        }

        if (cur.set) {
            vals.add(cur.value);
        }

        for (final Node n : cur.children.values()) {
            values(n, vals);
        }
    }

    public Set<String> keys()
    {
        final Set<String> vals = new HashSet<String>(size);
        final StringBuilder b = new StringBuilder();

        for (final Character c : root.children.keySet()) {
            b.setLength(0);
            keys(c, root.children.get(c), b, vals);
        }
        return vals;
    }

    public Iterator<String> keyIterator()
    {
        return new KeyIterator();
    }

    /**
     * Utility method to collect the keys.
     *
     * @param c
     *            the character under which the current node is filed in its parent node.
     * @param n
     *            the current node.
     * @param b
     *            a re-used string buffer in which the keys are manifested one after the other.
     * @param vals
     *            the found key values.
     */
    private void keys(final Character c, final Node n, final StringBuilder b,
            final Set<String> vals)
    {
        b.append(c);

        if (n.set) {
            vals.add(b.toString());
        }

        for (final Character cc : n.children.keySet()) {
            b.setLength(n.level);
            keys(cc, n.children.get(cc), b, vals);
        }
    }

    public class KeyIterator
        implements Iterator<String>
    {
        private final StringBuilder sb;
        private final Stack<Frame> stack;

        private class Frame
        {
            private final Character c;
            private final Node n;
            private final Iterator<Character> i;
            private boolean nodeDone;

            public Frame(final Character aChar, final Node aNode)
            {
                c = aChar;
                n = aNode;
                i = aNode.children.keySet().iterator();
                nodeDone = c == null || !n.set;
            }

            boolean hasNext()
            {
                return i.hasNext() || !nodeDone;
            }

            void step()
            {
                sb.append(c);
                sb.setLength(n.level);

                if (!nodeDone) {
                    // Render the node self once
                    nodeDone = true;
                }
                else {
                    // Render the children
                    final Character ch = i.next();
                    final Frame f = new Frame(ch, n.children.get(ch));
                    stack.add(f);
                    f.step();
                }
            }
        }

        {
            sb = new StringBuilder();
            stack = new Stack<Frame>();
            stack.push(new Frame(null, root));
            step();
        }

        private void step()
        {
            while (true) {
                // Return when there is nothing more to do.
                if (stack.isEmpty()) {
                    break;
                }

                final Frame f = stack.peek();

                if (f.hasNext()) {
                    f.step(); // Go to the next
                    break;
                }

                // Remove done stuff from the stack.
                while (!stack.isEmpty() && !stack.peek().hasNext()) {
                    stack.pop();
                }
            }
        }

        @Override
        public boolean hasNext()
        {
            return !stack.isEmpty();
        }

        @Override
        public String next()
        {
            final String s = sb.toString();
            step();
            return s;
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException("Remove not supported");
        }
    }
}
