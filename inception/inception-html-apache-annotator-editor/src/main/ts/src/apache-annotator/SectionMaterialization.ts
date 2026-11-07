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

/**
 * How much taller than its measured height a section has to be pinned so that the document after
 * it stays where it is once its content no longer fills it.
 * <p>
 * The bottom margin of a section's last paragraph collapses through the section's bottom edge: it
 * lies outside the section's box, so the measured height leaves it out. When the section is
 * secluded, its nested sections are taken out of layout and its content no longer fills the pinned
 * height. The margin then stays inside the section, and the document after it moves up by however
 * much of that margin no longer reaches out.
 */
export function endMarginLostWhenEmptied(element: Element): number {
    const inside = collapsedEndMargin(element);
    if (inside <= 0) return 0;
    return Math.max(0, inside - endMarginOutside(element));
}

/** The largest bottom margin among the last descendants whose margins collapse through the end. */
function collapsedEndMargin(element: Element): number {
    let margin = 0;
    let parent: Element = element;
    while (allowsEndCollapse(parent)) {
        const child = lastInFlowChild(parent);
        if (!child) break;
        margin = Math.max(margin, px(getComputedStyle(child).marginBottom));
        parent = child;
    }
    return margin;
}

/**
 * The margin that still collapses at the element's end once its content no longer contributes:
 * its own bottom margin, that of the ancestors it ends, and the top margin of what follows.
 */
function endMarginOutside(element: Element): number {
    let margin = 0;
    let current: Element = element;
    for (;;) {
        margin = Math.max(margin, px(getComputedStyle(current).marginBottom));
        const next = nextInFlowSibling(current);
        if (next !== undefined) {
            if (next) margin = Math.max(margin, collapsedStartMargin(next));
            return margin;
        }
        const parent = current.parentElement;
        if (!parent || !allowsEndCollapse(parent) || lastInFlowChild(parent) !== current) {
            return margin;
        }
        current = parent;
    }
}

/** The largest top margin among the element and the first descendants that collapse with it. */
function collapsedStartMargin(element: Element): number {
    let margin = px(getComputedStyle(element).marginTop);
    let parent: Element = element;
    while (allowsStartCollapse(parent)) {
        const child = firstInFlowChild(parent);
        if (!child) break;
        margin = Math.max(margin, px(getComputedStyle(child).marginTop));
        parent = child;
    }
    return margin;
}

function allowsEndCollapse(element: Element): boolean {
    const style = getComputedStyle(element);
    return (
        isPlainBlock(style) && px(style.borderBottomWidth) === 0 && px(style.paddingBottom) === 0
    );
}

function allowsStartCollapse(element: Element): boolean {
    const style = getComputedStyle(element);
    return isPlainBlock(style) && px(style.borderTopWidth) === 0 && px(style.paddingTop) === 0;
}

/** A block that does not start a formatting context of its own, so margins collapse through it. */
function isPlainBlock(style: CSSStyleDeclaration): boolean {
    return (
        (style.display === 'block' || style.display === 'list-item') &&
        style.overflowY === 'visible' &&
        style.float === 'none' &&
        (style.position === 'static' || style.position === 'relative')
    );
}

function isInFlow(element: Element): boolean {
    const style = getComputedStyle(element);
    return (
        style.display !== 'none' &&
        style.float === 'none' &&
        style.position !== 'absolute' &&
        style.position !== 'fixed'
    );
}

const hasText = (node: Node) => node.nodeType === Node.TEXT_NODE && !!node.textContent?.trim();

/** The last child laid out in the flow, or undefined if text or nothing comes last. */
function lastInFlowChild(element: Element): Element | undefined {
    for (let node = element.lastChild; node; node = node.previousSibling) {
        if (hasText(node)) return undefined;
        if (node instanceof Element && isInFlow(node)) return node;
    }
    return undefined;
}

function firstInFlowChild(element: Element): Element | undefined {
    for (let node = element.firstChild; node; node = node.nextSibling) {
        if (hasText(node)) return undefined;
        if (node instanceof Element && isInFlow(node)) return node;
    }
    return undefined;
}

/**
 * The next sibling laid out in the flow. Null if text comes first, which keeps margins from
 * collapsing across; undefined if nothing follows, so the element ends its parent.
 */
function nextInFlowSibling(element: Element): Element | null | undefined {
    for (let node = element.nextSibling; node; node = node.nextSibling) {
        if (hasText(node)) return null;
        if (node instanceof Element && isInFlow(node)) return node;
    }
    return undefined;
}

function px(value: string): number {
    return parseFloat(value) || 0;
}
