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

export function caretRangeFromPoint(clientX: number, clientY: number): Range | null {
    const range = document.createRange();

    // @ts-ignore
    if (document.caretPositionFromPoint) {
        // Use CSSOM-proprietary caretPositionFromPoint method
        // @ts-ignore
        const caretPosition = document.caretPositionFromPoint(clientX, clientY);
        if (!caretPosition) {
            console.error(`Unable to determine caret position from point: ${clientX},${clientY}`);
            return null;
        }
        range.setEnd(caretPosition.offsetNode, caretPosition.offset);
    } else if (document.caretRangeFromPoint) {
        // Use WebKit-proprietary fallback method
        const caretRange = document.caretRangeFromPoint(clientX, clientY);
        if (!caretRange) return null;
        range.setEnd(caretRange.startContainer, caretRange.startOffset);
    } else {
        // Neither method is supported, do nothing
        return null;
    }

    range.collapse();
    return range;
}

export function positionToOffset(root: Node, clientX: number, clientY: number): number {
    const range = caretRangeFromPoint(clientX, clientY);
    if (!range) return -1;
    range.setStart(root, 0);
    return range.toString().length;
}

export function calculateStartOffset(root: Node, element: Node): number {
    try {
        const range = document.createRange();
        range.setStart(root, 0);
        range.setEnd(element, 0);
        return range.toString().length;
    } catch (error) {
        console.error('Unable to determine start offset of element:', element, error);
        return -1;
    }
}

/**
 * The start offsets of several nodes, as {@link calculateStartOffset} gives them one by one. Each of
 * those calls copies all the text before its node, so for many nodes in a long document this takes a
 * single walk over the document instead.
 *
 * @return the offset of each node, in the order given; -1 for a node that is not {@code root} or
 *         below it.
 */
export function calculateStartOffsets(root: Node, nodes: Node[]): number[] {
    const wanted = new Set(nodes);
    const found = new Map<Node, number>();

    const doc = root.ownerDocument ?? (root as Document);
    const walker = doc.createTreeWalker(root, NodeFilter.SHOW_ALL);
    let offset = 0;
    for (let node: Node | null = walker.currentNode; node; node = walker.nextNode()) {
        if (wanted.has(node)) {
            found.set(node, offset);
            if (found.size === wanted.size) break;
        }
        // A range's text is that of its text nodes, CDATA sections included, but not comments
        if (node.nodeType === Node.TEXT_NODE || node.nodeType === Node.CDATA_SECTION_NODE) {
            offset += node.nodeValue?.length ?? 0;
        }
    }
    return nodes.map((n) => found.get(n) ?? -1);
}

export function calculateEndOffset(root: Node, element: Node): number {
    try {
        const range = document.createRange();
        range.selectNodeContents(element);
        range.setStart(root, 0);
        return range.toString().length;
    } catch (error) {
        console.error('Unable to determine end offset of element:', element, error);
        return -1;
    }
}

export function offsetToRange(root: Node, begin: number, end: number): Range | null {
    let base = 0;

    const ni = document.createNodeIterator(root, NodeFilter.SHOW_TEXT);
    let n = ni.nextNode();

    // Seek start node - find the text node that contains the begin offset
    while (n != null && base + n.textContent.length < begin) {
        base += n.textContent.length;
        n = ni.nextNode();
    }

    if (n == null) {
        // Did not find the begin offset....
        return null;
    }

    // When `begin` lands exactly on the trailing edge of this text node AND
    // there is a following text node, advance to offset 0 of that next node.
    // Otherwise the range would start at the trailing edge of e.g. a protected
    // element whose text ends at `begin`, and downstream rendering would expand
    // the highlight backward to cover that element. If there is no following
    // text node we keep `n` so a collapsed range at the end of all content
    // (`begin === totalLength`) remains valid. The end-seek below keeps `<` so
    // an end-boundary stays with the preceding run.
    if (begin === base + n.textContent.length) {
        const next = ni.nextNode();
        if (next != null) {
            base += n.textContent.length;
            n = next;
        }
    }

    const startNode = n;
    const startOffset = begin - base;

    // Seek end node - find the text node that contains the end offset
    while (n != null && base + n.textContent.length < end) {
        base += n.textContent.length;
        n = ni.nextNode();
    }

    if (n == null) {
        // Did not find the end offset....
        return null;
    }

    const endNode = n;
    const endOffset = end - base;

    const range = document.createRange();
    range.setStart(startNode, startOffset);
    range.setEnd(endNode, endOffset);
    return range;
}
