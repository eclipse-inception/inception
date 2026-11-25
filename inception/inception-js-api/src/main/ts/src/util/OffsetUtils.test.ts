/*
 * Licensed to the Technische Universität Darmstadt under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The Technische Universität Darmstadt
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { describe, it, expect } from 'vitest';
import {
    offsetToRange,
    calculateStartOffset,
    calculateStartOffsets,
    calculateEndOffset,
} from './OffsetUtils';

// Build a root <div> from an HTML string and attach it to the document so
// Range / NodeIterator have a live context.
function makeRoot(html: string): HTMLDivElement {
    const root = document.createElement('div');
    root.innerHTML = html;
    document.body.appendChild(root);
    return root;
}

describe('offsetToRange', () => {
    it('returns null when begin is past the end of all text', () => {
        const root = makeRoot('hello');
        expect(offsetToRange(root, 100, 200)).toBeNull();
    });

    it('returns null on an empty root', () => {
        const root = makeRoot('');
        expect(offsetToRange(root, 0, 0)).toBeNull();
    });

    it('produces a range within a single text node', () => {
        const root = makeRoot('hello world');
        const r = offsetToRange(root, 0, 5)!;
        expect(r).not.toBeNull();
        expect(r.toString()).toBe('hello');
    });

    it('produces a range spanning two text nodes across an element', () => {
        const root = makeRoot('one<span>two</span>three');
        // total text: "onetwothree"
        const r = offsetToRange(root, 2, 8)!;
        expect(r).not.toBeNull();
        expect(r.toString()).toBe('etwoth');
    });

    it('places a zero-width range at offset 0 of the first text node', () => {
        const root = makeRoot('abc');
        const r = offsetToRange(root, 0, 0)!;
        expect(r.startContainer.nodeType).toBe(Node.TEXT_NODE);
        expect(r.startContainer.nodeValue).toBe('abc');
        expect(r.startOffset).toBe(0);
        expect(r.toString()).toBe('');
    });

    it('places end at the trailing edge of the last text node when end == total length', () => {
        const root = makeRoot('abc<span>def</span>');
        // total = 6
        const r = offsetToRange(root, 0, 6)!;
        expect(r.toString()).toBe('abcdef');
        expect(r.endContainer.nodeType).toBe(Node.TEXT_NODE);
        expect((r.endContainer as Text).data).toBe('def');
        expect(r.endOffset).toBe(3);
    });

    // ---- Boundary cases — these are the spec for the fix ----

    describe('begin on an exact text-node boundary', () => {
        it('advances to offset 0 of the following text node (not end of previous)', () => {
            const root = makeRoot('abc<span>def</span>ghi');
            // boundary at offset 3 is between the "abc" text node and the "def" text node
            const r = offsetToRange(root, 3, 6)!;

            expect(r.toString()).toBe('def');
            expect(r.startContainer.nodeType).toBe(Node.TEXT_NODE);
            expect((r.startContainer as Text).data).toBe(
                'def'
                // failure hint
            );
            expect(r.startOffset).toBe(0);
        });

        it('skips an entire protected-like element when the annotation starts at its end', () => {
            // This mirrors the user-visible render bug: annotation [3,6] should start
            // AFTER the protected <span>, not inside it.
            const root = makeRoot('abc<span class="protected">PRO</span>tec');
            // total: "abcPROtec" -> protected occupies char [3,6], annotation = [6,9]
            const r = offsetToRange(root, 6, 9)!;

            expect(r.toString()).toBe('tec');
            expect(r.startContainer.nodeType).toBe(Node.TEXT_NODE);
            expect((r.startContainer as Text).data).toBe('tec');
            expect(r.startOffset).toBe(0);

            // The start container's parent must NOT be the protected span.
            const parent = r.startContainer.parentElement;
            expect(parent?.classList.contains('protected')).toBe(false);
        });

        it('crosses multiple adjacent zero-length seek steps cleanly', () => {
            // Two adjacent protected-like spans; begin lands between them.
            const root = makeRoot('x<span>AB</span><span>CD</span>y');
            // total: "xABCDy"; boundary at 3 sits between </span> and <span>
            const r = offsetToRange(root, 3, 5)!;

            expect(r.toString()).toBe('CD');
            expect((r.startContainer as Text).data).toBe('CD');
            expect(r.startOffset).toBe(0);
        });
    });

    describe('end on an exact text-node boundary', () => {
        it('stays at the end of the preceding text node (does not advance)', () => {
            // Asymmetry: an "end" landing exactly on a boundary belongs to the
            // PRECEDING run so the range still includes that text and the highlight
            // wraps just up to (and including) that content.
            const root = makeRoot('abc<span>def</span>ghi');
            // boundary at 3 between "abc" and "def"
            const r = offsetToRange(root, 0, 3)!;

            expect(r.toString()).toBe('abc');
            expect(r.endContainer.nodeType).toBe(Node.TEXT_NODE);
            expect((r.endContainer as Text).data).toBe('abc');
            expect(r.endOffset).toBe(3);
        });

        it('keeps an annotation ending where a protected element starts outside that element', () => {
            // Symmetric render case: annotation [0,3] ends exactly where the
            // protected <span> begins. Must NOT be pulled into the protected span.
            const root = makeRoot('abc<span class="protected">PRO</span>tec');
            const r = offsetToRange(root, 0, 3)!;

            expect(r.toString()).toBe('abc');
            const parent = r.endContainer.parentElement;
            expect(parent?.classList.contains('protected')).toBe(false);
        });
    });

    describe('zero-width range exactly on a boundary', () => {
        it('collapses to the start of the following text node', () => {
            const root = makeRoot('abc<span>def</span>ghi');
            const r = offsetToRange(root, 3, 3)!;
            expect(r.toString()).toBe('');
            expect(r.collapsed).toBe(true);
            // begin advances to next text node; end-seek starts from where
            // begin-seek left off and doesn't move backward, so both endpoints
            // land on the following text node at offset 0.
            expect((r.startContainer as Text).data).toBe('def');
            expect(r.startOffset).toBe(0);
            expect((r.endContainer as Text).data).toBe('def');
            expect(r.endOffset).toBe(0);
        });
    });

    it('returns a collapsed range at the end of content when begin == end == totalLength', () => {
        // Regression guard: the begin-seek must not advance past the last text
        // node when there is nothing following. Callers like `scrollTo(off, off)`
        // pass `off === totalLength` and expect a valid collapsed range, not null.
        const root = makeRoot('abc<span>def</span>');
        const total = 6;
        const r = offsetToRange(root, total, total)!;
        expect(r).not.toBeNull();
        expect(r.collapsed).toBe(true);
        expect((r.startContainer as Text).data).toBe('def');
        expect(r.startOffset).toBe(3);
    });

    it('round-trips with calculateStartOffset / calculateEndOffset for an interior element', () => {
        const root = makeRoot('aa<b>bbb</b>cc');
        const b = root.querySelector('b')!;
        const begin = calculateStartOffset(root, b); // 2
        const end = calculateEndOffset(root, b); // 5
        expect(begin).toBe(2);
        expect(end).toBe(5);

        const r = offsetToRange(root, begin, end)!;
        expect(r.toString()).toBe('bbb');
    });
});

describe('calculateStartOffsets', () => {
    it('gives each node the offset calculateStartOffset gives it', () => {
        const root = makeRoot(
            'intro<section><h2>one</h2><p>a <b>bold</b> word</p><!-- not text -->' +
                '<section><h3>nested</h3><p></p><p>after an empty one</p></section></section>tail'
        );
        const nodes = [root, ...Array.from(root.querySelectorAll('*'))];
        expect(calculateStartOffsets(root, nodes)).toEqual(
            nodes.map((node) => calculateStartOffset(root, node))
        );
    });

    it('keeps the order and the duplicates of the nodes asked for', () => {
        const root = makeRoot('<p>one</p><p>two</p>');
        const [first, second] = Array.from(root.querySelectorAll('p'));
        expect(calculateStartOffsets(root, [second, first, second])).toEqual([3, 0, 3]);
    });

    it('gives -1 for a node outside of the root', () => {
        const root = makeRoot('<p>one</p>');
        const other = makeRoot('<p>two</p>');
        expect(calculateStartOffsets(root, [other.firstChild!])).toEqual([-1]);
    });
});
