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

// Runs in a real Chromium (see vitest.config.js): the panes below are laid out by the browser, so
// section heights follow from the text wrapping at each pane's width instead of being stubbed.

import { describe, it, expect, afterEach } from 'vitest';
import {
    buildDocumentStructure,
    type DocumentStructureStrategy,
} from '@inception-project/inception-js-api';

import type { ViewportScrollTarget } from '@inception-project/inception-js-api';

import {
    ApacheAnnotatorSyncController,
    DEFAULT_SEGMENT_BAND,
} from './ApacheAnnotatorSyncController';
import { endMarginLostWhenEmptied } from './SectionMaterialization';
// The hub lives in the external editor package; this test drives it with two real panes
import { ViewportSyncHub } from '../../../../../../inception-external-editor/src/main/ts/src/ViewportSyncHub';

const PANE_HEIGHT = 400;

const PARAGRAPH =
    'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ' +
    'ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ' +
    'ullamco laboris nisi ut aliquip ex ea commodo consequat.';

const strategy: DocumentStructureStrategy = {
    sectionSelector: 'section',
    preprocess: () => {},
    extractTitle: (s) => (s as HTMLElement).dataset.title,
    extractKey: (s) => (s as HTMLElement).dataset.key,
    scrollTarget: (s) => s,
};

interface Pane {
    container: HTMLElement;
    controller: ApacheAnnotatorSyncController;
    section: (key: string) => HTMLElement;
}

const panes: Pane[] = [];

afterEach(() => {
    while (panes.length) panes.pop()!.container.remove();
});

function paragraph(): HTMLParagraphElement {
    const p = document.createElement('p');
    p.textContent = PARAGRAPH;
    p.style.margin = '0 0 1em';
    return p;
}

/** A keyed section with a heading and the given number of paragraphs. */
function keyedSection(key: string, paragraphs: number): HTMLElement {
    const section = document.createElement('section');
    section.dataset.key = key;
    section.dataset.title = `Chapter ${key}`;
    const heading = document.createElement('h2');
    heading.textContent = `Chapter ${key}`;
    heading.style.margin = '0';
    section.appendChild(heading);
    for (let i = 0; i < paragraphs; i++) section.appendChild(paragraph());
    return section;
}

/**
 * A scroll pane of the given width whose document is filled by {@code fill}.
 */
function build(width: number, fill: (root: HTMLElement) => void): Pane {
    const container = document.createElement('div');
    // position: relative makes the container the sections' offsetParent, so a section's offsetTop
    // is its position in the scrolled content. Without scroll anchoring the browser does not
    // adjust scrollTop to hide layout shifts (Safari has none), so the tests see them.
    container.style.cssText = `position: relative; overflow: auto; overflow-anchor: none; width: ${width}px; height: ${PANE_HEIGHT}px; font: 16px/1.5 serif;`;

    const root = document.createElement('div');
    const title = document.createElement('h1');
    title.textContent = 'Title';
    root.appendChild(title);
    fill(root);
    container.appendChild(root);
    document.body.appendChild(container);

    const toc = buildDocumentStructure(root, strategy);
    const controller = new ApacheAnnotatorSyncController(root, () => container);
    // The tests read where a sync puts the viewport right away
    controller.glide = false;
    controller.setDocumentStructure({
        sectionSelector: () => strategy.sectionSelector,
        extractKey: (s) => strategy.extractKey(s),
        tocRoot: () => toc,
    });

    const p: Pane = {
        container,
        controller,
        section: (key) => root.querySelector(`section[data-key="${key}"]`) as HTMLElement,
    };
    panes.push(p);
    return p;
}

/** A pane showing a title followed by one keyed section per chapter number. */
function pane(width: number, chapters: [number, number][]): Pane {
    return build(width, (root) => {
        for (const [n, paragraphs] of chapters)
            root.appendChild(keyedSection(`ch${n}`, paragraphs));
    });
}

/**
 * A section's extent in the scrolled content: from its top to the next section's top. That is how
 * the controller delimits a region, and unlike offsetHeight it includes the bottom margin of the
 * section's last paragraph, which collapses through the section's border.
 */
function extent(p: Pane, key: string): { top: number; height: number } {
    const section = p.section(key);
    const next = section.nextElementSibling as HTMLElement;
    return { top: section.offsetTop, height: next.offsetTop - section.offsetTop };
}

/** Where the pane's sync point is in its scrolled content. */
function syncPoint(p: Pane): number {
    return p.container.scrollTop + p.controller.syncPointOffset(p.container);
}

/** Scroll the sender so its sync point is the given fraction into a section, then sync. */
function syncFrom(sender: Pane, receiver: Pane, key: string, fraction: number) {
    const { top, height } = extent(sender, key);
    sender.container.scrollTop = sender.controller.scrollTopForSyncPoint(
        sender.container,
        top + fraction * height
    );

    const pos = sender.controller.getViewportScrollPosition();
    expect(pos?.sectionKey).toBe(key);
    receiver.controller.reset();
    receiver.controller.scrollToViewportPosition(pos!);
}

/**
 * Expects the pane's sync point to lie at the given fraction of the section. The tolerance is in
 * pixels, not fraction units, so it does not grow with the section. It is two pixels because
 * scrollTop is whole pixels and on the start ramp (see syncPointFraction) the sync point moves about
 * twice as fast as scrollTop, so one pixel of rounding shows as two at the sync point.
 */
function expectSyncPointAt(p: Pane, key: string, fraction: number) {
    const { top, height } = extent(p, key);
    expect(Math.abs(syncPoint(p) - (top + fraction * height))).toBeLessThanOrEqual(2);
}

const CHAPTERS: [number, number][] = [
    [1, 4],
    [2, 6],
    [3, 3],
    [4, 6],
    [5, 8],
];

describe('ApacheAnnotatorSyncController in a real browser', () => {
    it('lays out the panes for real', () => {
        const wide = pane(700, CHAPTERS);
        const narrow = pane(300, CHAPTERS);

        // Guards the premise of the tests below: without real layout both would be 0
        expect(wide.container.scrollHeight).toBeGreaterThan(PANE_HEIGHT);
        expect(narrow.section('ch2').offsetHeight).toBeGreaterThan(
            wide.section('ch2').offsetHeight
        );
    });

    it('keeps the sync point at the same place in a section across panes of different widths', () => {
        const wide = pane(700, CHAPTERS);
        const narrow = pane(300, CHAPTERS);

        for (const [key, fraction] of [
            ['ch1', 0.5],
            ['ch2', 0.25],
            ['ch3', 0.75],
            ['ch4', 0.1],
        ] as [string, number][]) {
            syncFrom(wide, narrow, key, fraction);
            expectSyncPointAt(narrow, key, fraction);

            syncFrom(narrow, wide, key, fraction);
            expectSyncPointAt(wide, key, fraction);
        }
    });

    it('puts the sync point half a viewport down in the middle of the document', () => {
        const p = pane(500, CHAPTERS);
        const max = p.container.scrollHeight - p.container.clientHeight;
        p.container.scrollTop = Math.round(max / 2);

        // Away from both ends the sync point sits at the middle of the viewport, measured in layout
        expect(p.container.scrollTop).toBeGreaterThan(PANE_HEIGHT);
        expect(p.container.scrollTop).toBeLessThan(max - PANE_HEIGHT);
        expect(
            Math.abs(p.controller.syncPointOffset(p.container) - PANE_HEIGHT / 2)
        ).toBeLessThanOrEqual(1);
    });

    it('creeps through the end of the section before while the sender is in a section the receiver lacks', () => {
        const source = pane(500, CHAPTERS);
        const gap = pane(
            500,
            CHAPTERS.filter(([n]) => n !== 3)
        );

        const band = DEFAULT_SEGMENT_BAND * gap.container.clientHeight;
        const ch4 = gap.section('ch4').offsetTop;
        let previous = -Infinity;
        for (const fraction of [0, 0.5, 0.99]) {
            syncFrom(source, gap, 'ch3', fraction);
            // Within the band at the end of ch2, moving forward. Within a pixel: the browser may
            // snap scrollTop to device pixels.
            expect(syncPoint(gap)).toBeGreaterThanOrEqual(ch4 - band - 1);
            expect(syncPoint(gap)).toBeLessThanOrEqual(ch4 + 1);
            expect(syncPoint(gap)).toBeGreaterThan(previous);
            previous = syncPoint(gap);
        }
    });

    it('maps back to where it came from when the other pane takes the lead', () => {
        // The screenshot case: one pane scrolls in a section the other lacks, then the other pane
        // is scrolled, and the first must not jump
        const source = pane(500, CHAPTERS);
        const gap = pane(
            500,
            CHAPTERS.filter(([n]) => n !== 3)
        );

        for (const [key, fraction] of [
            ['ch2', 0.5],
            ['ch3', 0.2],
            ['ch3', 0.8],
        ] as [string, number][]) {
            syncFrom(source, gap, key, fraction);
            const before = syncPoint(source);

            source.controller.reset();
            source.controller.scrollToViewportPosition(gap.controller.getViewportScrollPosition()!);
            // A few pixels: scrollTop is whole pixels on both sides
            expect(Math.abs(syncPoint(source) - before)).toBeLessThanOrEqual(3);
        }
    });
});

/** The deepest paragraph or heading whose box straddles the seam, or null. */
function deepestTextBlockAt(p: Pane, seamY: number): HTMLElement | null {
    let best: HTMLElement | null = null;
    let bestDepth = -1;
    p.container.querySelectorAll<HTMLElement>('p, h1, h2').forEach((el) => {
        const r = el.getBoundingClientRect();
        if (r.top > seamY || r.bottom <= seamY) return;
        let depth = 0;
        for (let n: Element | null = el; n; n = n.parentElement) depth++;
        if (depth > bestDepth) {
            best = el;
            bestDepth = depth;
        }
    });
    return best;
}

/** Every section's own box, as the seclusion observer decides it: far outside the viewport. */
function setSeclusion(p: Pane, secluded: boolean) {
    const viewport = p.container.getBoundingClientRect();
    const sections = Array.from(p.container.querySelectorAll<HTMLElement>('section'));
    const far = sections.map((s) => {
        const r = s.getBoundingClientRect();
        return r.bottom < viewport.top - 1000 || r.top > viewport.bottom + 1000;
    });
    sections.forEach((s, i) => s.classList.toggle('iaa-secluded', secluded && far[i]));
}

/**
 * What the visualizer's optimizeLayout does before secluding: pin each section's height
 * (materializeWidthAndHeight), so hiding a section does not reflow the document around it, and let
 * the sync record where everything is.
 */
function materialize(p: Pane) {
    // Measure everything first, then write, as the visualizer does
    const sections = Array.from(p.container.querySelectorAll<HTMLElement>('section'));
    const heights = sections.map(
        (s) => s.getBoundingClientRect().height + endMarginLostWhenEmptied(s)
    );
    sections.forEach((s, i) => {
        s.style.boxSizing = 'border-box';
        s.style.minHeight = `${heights[i]}px`;
    });
    p.controller.recordLayout();
}

/**
 * The seclusion rules of ApacheAnnotatorEditor.scss, copied here (the stylesheet needs the build to
 * compile).
 */
const SECLUSION_CSS = `
    .iaa-secluded { visibility: hidden !important; }
    .iaa-secluded .iaa-secluded { display: none !important; }
`;

/**
 * A pane where "part" holds the nested chapters "a1" and "a2" followed by more text in "part"
 * itself, and "b" follows "part". "b" is tall enough that the viewport can sit well past "part",
 * which then hides "a1" and "a2".
 */
function nestedPane(bMarginTop = '0'): Pane {
    return build(500, (root) => {
        const part = keyedSection('part', 1);
        part.appendChild(keyedSection('a1', 30));
        part.appendChild(keyedSection('a2', 10));
        for (let i = 0; i < 20; i++) part.appendChild(paragraph());
        root.appendChild(part);
        const b = keyedSection('b', 40);
        b.style.marginTop = bMarginTop;
        root.appendChild(b);
    });
}

describe('ApacheAnnotatorSyncController regressions', () => {
    it('moves nothing on screen when sections are secluded', () => {
        // Hiding a nested section moves the text after it in the section around it, but that
        // section is then secluded too, so far off screen, and its pinned height keeps the rest of
        // the document where it was
        const p = nestedPane();
        const style = document.createElement('style');
        style.textContent = SECLUSION_CSS;
        document.head.appendChild(style);
        materialize(p);

        const paragraphs = Array.from(p.container.querySelectorAll('p'));
        const max = p.container.scrollHeight - p.container.clientHeight;
        const moved: string[] = [];
        for (let s = 0; s <= max; s += 20) {
            p.container.scrollTop = s;
            setSeclusion(p, false);
            const viewport = p.container.getBoundingClientRect();
            const onScreen = paragraphs.filter((el) => {
                const r = el.getBoundingClientRect();
                return r.bottom > viewport.top && r.top < viewport.bottom;
            });
            const plain = onScreen.map((el) => el.getBoundingClientRect().top);
            setSeclusion(p, true);
            onScreen.forEach((el, i) => {
                const top = el.getBoundingClientRect().top;
                if (Math.abs(top - plain[i]) > 1) {
                    moved.push(`scrollTop ${s}: ${plain[i].toFixed(1)} -> ${top.toFixed(1)}`);
                }
            });
        }
        setSeclusion(p, false);
        style.remove();

        expect(moved).toEqual([]);
    });

    it('keeps what follows a secluded section in place whatever margin it has', () => {
        // The last paragraph's bottom margin collapses out of "part"; once "part" is secluded and
        // its nested sections are hidden, it would stay inside unless the pinned height allows for
        // it. How much of it is lost depends on the margin of what follows.
        const moved: string[] = [];
        for (const marginTop of ['0', '10px', '30px']) {
            const p = nestedPane(marginTop);
            const style = document.createElement('style');
            style.textContent = SECLUSION_CSS;
            document.head.appendChild(style);
            materialize(p);

            const b = p.section('b');
            const max = p.container.scrollHeight - p.container.clientHeight;
            for (let s = 0; s <= max; s += 200) {
                p.container.scrollTop = s;
                setSeclusion(p, false);
                const plain = [b.offsetTop, p.container.scrollHeight];
                setSeclusion(p, true);
                const secluded = [b.offsetTop, p.container.scrollHeight];
                if (plain.some((v, i) => Math.abs(v - secluded[i]) > 1)) {
                    moved.push(`margin ${marginTop}, scrollTop ${s}: ${plain} -> ${secluded}`);
                }
            }
            setSeclusion(p, false);
            style.remove();
        }

        expect(moved).toEqual([]);
    });

    it('reports the paragraph that straddles the sync point, not the section around it', () => {
        const p = pane(500, CHAPTERS);
        const max = p.container.scrollHeight - p.container.clientHeight;
        const wrong: string[] = [];

        for (let s = 0; s <= max; s += 2) {
            p.container.scrollTop = s;
            const seamY =
                p.container.getBoundingClientRect().top + p.controller.syncPointOffset(p.container);
            const block = deepestTextBlockAt(p, seamY);
            const pos = p.controller.getViewportScrollPosition();
            if (!block || !pos) continue;

            const r = block.getBoundingClientRect();
            const expected = (seamY - r.top) / r.height;
            if (Math.abs(pos.fraction - expected) > 0.02) {
                wrong.push(
                    `scrollTop ${s}: ${block.tagName} expected ${expected.toFixed(2)}, got ${pos.fraction.toFixed(2)}`
                );
            }
        }

        expect(wrong).toEqual([]);
    });

    it('scrolls to a nested region the same whether or not that region is secluded', () => {
        const sender = nestedPane();
        const receiver = nestedPane();
        const style = document.createElement('style');
        style.textContent = SECLUSION_CSS;
        document.head.appendChild(style);
        materialize(sender);
        materialize(receiver);

        // a1 is hidden itself; a2 is hidden and comes after the hidden a1. A region runs up to the
        // next region's start.
        const diffs: string[] = [];
        for (const [key, next] of [
            ['a1', 'a2'],
            ['a2', 'b'],
        ]) {
            // The sender's sync point sits halfway through the region
            const top = sender.section(key).offsetTop;
            const target = top + (sender.section(next).offsetTop - top) / 2;
            sender.container.scrollTop = sender.controller.scrollTopForSyncPoint(
                sender.container,
                target
            );
            const pos = sender.controller.getViewportScrollPosition();
            expect(pos?.sectionKey).toBe(key);

            const max = receiver.container.scrollHeight - receiver.container.clientHeight;
            for (let s = 0; s <= max; s += 20) {
                const landed = (secluded: boolean) => {
                    receiver.container.scrollTop = s;
                    setSeclusion(receiver, secluded);
                    receiver.controller.reset();
                    receiver.controller.scrollToViewportPosition(pos!);
                    return receiver.container.scrollTop;
                };
                const plain = landed(false);
                const secluded = landed(true);
                if (Math.abs(plain - secluded) > 1) {
                    diffs.push(`${key} from scrollTop ${s}: plain ${plain}, secluded ${secluded}`);
                }
            }
        }
        setSeclusion(receiver, false);
        style.remove();

        expect(diffs).toEqual([]);
    });

    it('reports the same position from a pane whose sections around it are secluded', () => {
        // The sender's own report depends on where its regions start, a2 among them, which is
        // hidden while the sender is in b
        const p = nestedPane();
        const style = document.createElement('style');
        style.textContent = SECLUSION_CSS;
        document.head.appendChild(style);
        materialize(p);

        const max = p.container.scrollHeight - p.container.clientHeight;
        const diffs: string[] = [];
        for (let s = 0; s <= max; s += 20) {
            p.container.scrollTop = s;
            setSeclusion(p, false);
            const plain = p.controller.getViewportScrollPosition();
            setSeclusion(p, true);
            const secluded = p.controller.getViewportScrollPosition();
            // Recorded and live tops are taken at different scroll positions, so allow rounding
            const plainSections = plain?.sections ?? [];
            const secludedSections = secluded?.sections ?? [];
            const same =
                plain?.sectionKey === secluded?.sectionKey &&
                Math.abs((plain?.sectionFraction ?? 0) - (secluded?.sectionFraction ?? 0)) <
                    0.001 &&
                plainSections.length === secludedSections.length &&
                plainSections.every(
                    (section, i) =>
                        section.key === secludedSections[i].key &&
                        Math.abs(section.top - secludedSections[i].top) < 0.0001
                );
            if (!same) {
                diffs.push(
                    `scrollTop ${s}: ${plain?.sectionKey}/${plain?.sectionFraction?.toFixed(3)} vs ` +
                        `${secluded?.sectionKey}/${secluded?.sectionFraction?.toFixed(3)}`
                );
            }
        }
        setSeclusion(p, false);
        style.remove();

        expect(diffs).toEqual([]);
    });
});

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

describe('ViewportSyncHub with two panes', () => {
    it('keeps a genuine scroll that starts within the echo window after a sync', async () => {
        const a = pane(500, CHAPTERS);
        const b = pane(500, CHAPTERS);
        const hub = new ViewportSyncHub();
        // Counts how often the hub moves each pane, to catch echoes bouncing between them
        const moves = { a: 0, b: 0 };
        const adapt = (p: Pane, id: 'a' | 'b') =>
            ({
                getViewportScrollPosition: () => p.controller.getViewportScrollPosition(),
                scrollToViewportPosition: (t: ViewportScrollTarget) => {
                    moves[id]++;
                    p.controller.scrollToViewportPosition(t);
                },
            }) as unknown as Parameters<ViewportSyncHub['register']>[1];
        hub.register('a', adapt(a, 'a'), a.container);
        hub.register('b', adapt(b, 'b'), b.container);
        hub.link('a', 'b');

        // The user scrolls a; b follows and its echo is not passed back
        a.container.scrollTop = 300;
        await sleep(100);

        // The user then scrolls b right after a's move, so it must not be mistaken for the echo
        b.container.scrollTop = 900;
        await sleep(600);

        hub.unregister('a');
        hub.unregister('b');

        // Same content and width, so the panes should line up at the same scroll position
        expect(Math.abs(a.container.scrollTop - b.container.scrollTop)).toBeLessThanOrEqual(2);
        // One move for a's scroll, one for b's, and no echo of either
        expect(moves.a + moves.b).toBeLessThanOrEqual(2);
    });
});
