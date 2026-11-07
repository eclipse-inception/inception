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
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
    buildDocumentStructure,
    type SectionTop,
    type TocLevel,
    type ViewportScrollPosition,
    type ViewportScrollTarget,
} from '@inception-project/inception-js-api';

import {
    ApacheAnnotatorSyncController,
    blendTowardScrollProgress,
    buildSyncRegions,
    LEADING_REGION_KEY,
    resolveSegmentTarget,
    sameScrollProgress,
    scrollTopForSyncPointAt,
    syncPointFraction,
    type SegmentRegion,
    type SyncRegion,
} from './ApacheAnnotatorSyncController';
import { calculateEndOffset } from '@inception-project/inception-js-api';

// The band over which the blend ramps, mirrored from the implementation. Kept as a literal rather
// than imported so a change to the constant makes these expectations fail loudly instead of
// silently re-deriving themselves.
const BAND = 0.15;
const MAX_SCROLL = 1000;

describe('blendTowardScrollProgress', () => {
    describe('pass-through cases', () => {
        it('returns the anchored top unchanged when the source sent no progress', () => {
            // The pre-extension wire format: peers that never send progress must keep the old
            // pure offset-anchored behaviour.
            expect(blendTowardScrollProgress(400, undefined, MAX_SCROLL)).toBe(400);
        });

        it('returns the anchored top unchanged when the container cannot scroll', () => {
            expect(blendTowardScrollProgress(400, 0.5, 0)).toBe(400);
        });

        it('returns the anchored top unchanged for a negative scroll range', () => {
            // scrollHeight < clientHeight can yield a negative maxScroll; treat as unscrollable
            // rather than blending toward a nonsensical negative progressTop.
            expect(blendTowardScrollProgress(400, 0.5, -50)).toBe(400);
        });
    });

    describe('at the extremes, progress wins exactly', () => {
        // The whole point of the blend: an offset anchor lands short of the extreme because the two
        // editors hold different amounts of content below the shared top block. At p=0/p=1 the
        // anchored value must be overridden completely, or scrolling to an end never quite arrives.
        it('snaps to the very top at progress 0, ignoring a short anchored top', () => {
            expect(blendTowardScrollProgress(120, 0, MAX_SCROLL)).toBe(0);
        });

        it('snaps to the very bottom at progress 1, ignoring a short anchored top', () => {
            expect(blendTowardScrollProgress(880, 1, MAX_SCROLL)).toBe(MAX_SCROLL);
        });
    });

    describe('in the interior, the offset anchor wins', () => {
        it('leaves the anchored top untouched at the exact band edge', () => {
            // nearness hits 0 precisely at BAND in from an extreme.
            expect(blendTowardScrollProgress(400, BAND, MAX_SCROLL)).toBeCloseTo(400);
            expect(blendTowardScrollProgress(400, 1 - BAND, MAX_SCROLL)).toBeCloseTo(400);
        });

        it('leaves the anchored top untouched in mid-document', () => {
            expect(blendTowardScrollProgress(400, 0.5, MAX_SCROLL)).toBeCloseTo(400);
        });

        it('leaves the anchored top untouched well outside the band', () => {
            expect(blendTowardScrollProgress(250, 0.25, MAX_SCROLL)).toBeCloseTo(250);
            expect(blendTowardScrollProgress(750, 0.75, MAX_SCROLL)).toBeCloseTo(750);
        });
    });

    describe('within the band, the two regimes mix', () => {
        it('weights progress and anchor evenly at half the band from the top', () => {
            // p = BAND/2 -> nearness = 0.5, so the result is the midpoint of the two candidates.
            const p = BAND / 2;
            const progressTop = p * MAX_SCROLL; // 75
            const anchoredTop = 200;
            expect(blendTowardScrollProgress(anchoredTop, p, MAX_SCROLL)).toBeCloseTo(
                0.5 * progressTop + 0.5 * anchoredTop
            );
        });

        it('weights progress and anchor evenly at half the band from the bottom', () => {
            const p = 1 - BAND / 2;
            const progressTop = p * MAX_SCROLL; // 925
            const anchoredTop = 800;
            expect(blendTowardScrollProgress(anchoredTop, p, MAX_SCROLL)).toBeCloseTo(
                0.5 * progressTop + 0.5 * anchoredTop
            );
        });

        it('moves monotonically from the anchor toward progress as an extreme nears', () => {
            // Approaching the top with an anchor that overshoots it: each step must pull further
            // toward progressTop, never bounce back. A non-monotonic ramp would read as jitter.
            const anchoredTop = 300;
            const results = [BAND, BAND * 0.75, BAND * 0.5, BAND * 0.25, 0].map((p) =>
                blendTowardScrollProgress(anchoredTop, p, MAX_SCROLL)
            );
            for (let i = 1; i < results.length; i++) {
                expect(results[i]).toBeLessThan(results[i - 1]);
            }
        });

        it('is continuous across the band edge, so there is no snap', () => {
            // Just inside vs. just outside the band must differ only marginally - the property that
            // makes blending preferable to switching regimes at a threshold.
            const anchoredTop = 300;
            const outside = blendTowardScrollProgress(anchoredTop, BAND + 0.001, MAX_SCROLL);
            const inside = blendTowardScrollProgress(anchoredTop, BAND - 0.001, MAX_SCROLL);
            expect(Math.abs(outside - inside)).toBeLessThan(2);
        });
    });
});

describe('syncPointFraction', () => {
    const H = 500;
    // One viewport height per transition, so the expectations below read in whole viewports
    const ONE = { start: 1, end: 1 };

    it('takes half a viewport height for each transition by default', () => {
        expect(syncPointFraction(125, 5000, H)).toBe(0.25);
        expect(syncPointFraction(250, 5000, H)).toBe(0.5);
        expect(syncPointFraction(4750, 5000, H)).toBe(0.5);
        expect(syncPointFraction(5000, 5000, H)).toBe(1);
    });

    it('is at the viewport top at the start of the document', () => {
        expect(syncPointFraction(0, 5000, H, ONE)).toBe(0);
    });

    it('reaches the middle of the viewport after one viewport height and stays there', () => {
        expect(syncPointFraction(250, 5000, H, ONE)).toBe(0.25);
        expect([500, 2500, 4500].map((t) => syncPointFraction(t, 5000, H, ONE))).toEqual([
            0.5, 0.5, 0.5,
        ]);
    });

    it('moves to the viewport bottom over the last viewport height', () => {
        expect(syncPointFraction(4750, 5000, H, ONE)).toBe(0.75);
        expect(syncPointFraction(5000, 5000, H, ONE)).toBe(1);
    });

    it('lets the ramps meet halfway when the scroll range is shorter than two viewport heights', () => {
        expect([0, 150, 300, 450, 600].map((t) => syncPointFraction(t, 600, H, ONE))).toEqual([
            0, 0.25, 0.5, 0.75, 1,
        ]);
    });

    it('stays at the top when there is nothing to scroll', () => {
        expect(syncPointFraction(0, 0, H)).toBe(0);
    });

    it('takes the configured number of viewport heights for each transition', () => {
        const ramps = { start: 1.5, end: 0.5 };
        expect(syncPointFraction(375, 5000, H, ramps)).toBe(0.25);
        expect(syncPointFraction(750, 5000, H, ramps)).toBe(0.5);
        expect(syncPointFraction(4750, 5000, H, ramps)).toBe(0.5);
        expect(syncPointFraction(4875, 5000, H, ramps)).toBe(0.75);
    });

    it('shrinks unequal transitions in proportion when they do not fit the scroll range', () => {
        // 1.5 + 0.5 viewport heights = 1000 px, scaled to the 500 px range: 375 and 125
        const ramps = { start: 1.5, end: 0.5 };
        expect([0, 375, 437.5, 500].map((t) => syncPointFraction(t, 500, H, ramps))).toEqual([
            0, 0.5, 0.75, 1,
        ]);
    });

    it('runs the sync point from the top to the bottom of the content, only ever forward', () => {
        for (const maxScroll of [5000, 600, 100]) {
            let previous = -1;
            for (let t = 0; t <= maxScroll; t += maxScroll / 50) {
                const syncPoint = t + syncPointFraction(t, maxScroll, H) * H;
                expect(syncPoint).toBeGreaterThan(previous);
                previous = syncPoint;
            }
            expect(previous).toBeCloseTo(maxScroll + H, 6);
        }
    });
});

describe('scrollTopForSyncPointAt', () => {
    const H = 500;

    it('inverts the sync point on the ramps and on the plateau', () => {
        for (const ramps of [undefined, { start: 1.5, end: 0.5 }, { start: 0.2, end: 3 }]) {
            for (const maxScroll of [5000, 600, 100]) {
                for (let t = 0; t <= maxScroll; t += maxScroll / 50) {
                    const syncPoint = t + syncPointFraction(t, maxScroll, H, ramps) * H;
                    expect(scrollTopForSyncPointAt(syncPoint, maxScroll, H, ramps)).toBeCloseTo(
                        t,
                        6
                    );
                }
            }
        }
    });

    it('clamps positions outside the content to its ends', () => {
        expect(scrollTopForSyncPointAt(-100, 5000, H)).toBe(0);
        expect(scrollTopForSyncPointAt(9999, 5000, H)).toBe(5000);
    });

    it('stays at the top when there is nothing to scroll', () => {
        expect(scrollTopForSyncPointAt(300, 0, H)).toBe(0);
    });
});

describe('sameScrollProgress', () => {
    // This guards the dedup key in ApacheAnnotatorSyncController: near an extreme, two positions
    // agreeing on begin/fraction can still blend to different pixels, so progress must take part in
    // the comparison or those scrolls get dropped as duplicates.
    it('treats values within the dedup tolerance as the same', () => {
        expect(sameScrollProgress(0.5, 0.5)).toBe(true);
        expect(sameScrollProgress(0.5, 0.505)).toBe(true);
    });

    it('treats values beyond the dedup tolerance as different', () => {
        expect(sameScrollProgress(0.5, 0.52)).toBe(false);
        expect(sameScrollProgress(0, 1)).toBe(false);
    });

    it('treats two absent values as the same', () => {
        expect(sameScrollProgress(undefined, undefined)).toBe(true);
    });

    it('never matches a present value against an absent one', () => {
        // Losing progress switches the receiver from the blended target back to the bare offset
        // anchor - a real move, not a no-op.
        expect(sameScrollProgress(0.5, undefined)).toBe(false);
        expect(sameScrollProgress(undefined, 0.5)).toBe(false);
        // Zero is a present value, not an absence - the top extreme must not read as "no progress".
        expect(sameScrollProgress(0, undefined)).toBe(false);
    });
});

describe('buildSyncRegions', () => {
    // Regions partition the document by keyed-section START offsets into a flat, non-overlapping
    // list. calculateStartOffset is character-based (not layout), so this is exercisable in jsdom.
    let container: HTMLElement;

    beforeEach(() => {
        container = document.createElement('div');
        document.body.appendChild(container);
    });

    afterEach(() => {
        while (document.body.firstChild) {
            document.body.removeChild(document.body.firstChild);
        }
    });

    // A minimal structure strategy: sections are <sec> elements; the key is their data-key attr.
    const strategy = {
        sectionSelector: 'sec',
        preprocess: () => {},
        extractTitle: (s: Element) => s.getAttribute('data-title') ?? undefined,
        extractKey: (s: Element) => s.getAttribute('data-key') ?? undefined,
    };

    function sec(text: string, key: string | undefined, ...children: Node[]): HTMLElement {
        const e = document.createElement('sec');
        e.setAttribute('data-title', text);
        if (key !== undefined) e.setAttribute('data-key', key);
        e.appendChild(document.createTextNode(text));
        for (const c of children) e.appendChild(c);
        return e;
    }

    function regions() {
        const toc: TocLevel = buildDocumentStructure(container, strategy);
        return buildSyncRegions(container, toc, calculateEndOffset(container, container));
    }

    it('partitions the document into non-overlapping regions in document order', () => {
        // A leading paragraph (no section), then two sibling keyed sections.
        container.appendChild(document.createTextNode('intro'));
        container.appendChild(sec('AAAA', 'k-a'));
        container.appendChild(sec('BB', 'k-b'));

        const rs = regions();
        // leading [0,5) then k-a [5,9) then k-b [9,11)
        expect(rs.map((r) => r.key)).toEqual([LEADING_REGION_KEY, 'k-a', 'k-b']);
        expect(rs.map((r) => [r.start, r.end])).toEqual([
            [0, 5],
            [5, 9],
            [9, 11],
        ]);
        // No overlap: each region's end is the next region's start.
        for (let i = 1; i < rs.length; i++) expect(rs[i].start).toBe(rs[i - 1].end);
    });

    it('uses a nested section START as a boundary, running the parent region only up to it', () => {
        // Chapter "CH" contains child "SUB"; the chapter region must END where SUB begins, not
        // continue underneath it -- that non-overlap is the whole point.
        const sub = sec('SUB', 'k-sub');
        const chapter = sec('CH', 'k-ch', sub);
        container.appendChild(chapter);

        const rs = regions();
        // "CH" text is offset 0..2, then SUB starts at 2.
        expect(rs.map((r) => r.key)).toEqual(['k-ch', 'k-sub']);
        expect(rs[0].start).toBe(0);
        expect(rs[0].end).toBe(2); // chapter region stops at SUB start
        expect(rs[1].start).toBe(2); // sub region starts there, no overlap
    });

    it('ignores keyless sections (their content belongs to the enclosing region)', () => {
        container.appendChild(sec('AAAA', 'k-a', sec('xx', undefined)));

        const rs = regions();
        expect(rs.map((r) => r.key)).toEqual(['k-a']);
    });

    it('keeps the first key when two sections share a start offset / key', () => {
        // Two sections at the same offset can't both open a region; first in doc order wins.
        const first = sec('First', 'dup');
        container.appendChild(first);
        // second section immediately after, distinct offset, same key -> deduped by key
        container.appendChild(sec('Second', 'dup'));

        const rs = regions();
        // leading region is empty (first section at 0), so only the 'dup' region(s) — deduped to one
        expect(rs.filter((r) => r.key === 'dup').length).toBe(1);
        // and it is the first section's region, which starts at 0, not the second's at 5
        expect(rs.find((r) => r.key === 'dup')?.start).toBe(0);
    });

    it('produces a single leading region spanning the whole document when there are no sections', () => {
        container.appendChild(document.createTextNode('just text'));
        const rs = regions();
        expect(rs.length).toBe(1);
        expect(rs[0].key).toBe(LEADING_REGION_KEY);
        expect(rs[0].start).toBe(0);
        expect(rs[0].end).toBe('just text'.length);
    });
});

/**
 * The sender's regions with where each starts, in the unit of scroll progress, and optionally the
 * key of the region enclosing it.
 */
function sender(...entries: ([string, number] | [string, number, string])[]): SectionTop[] {
    return entries.map(([key, top, parent]) => ({ key, top, parent }));
}

describe('resolveSegmentTarget', () => {
    /** The receiver's regions with their tops in content pixels, and optionally their parent. */
    function local(...entries: ([string, number] | [string, number, string])[]): SegmentRegion[] {
        return entries.map(([key, top, parentKey]) => ({ key, top, parentKey }));
    }

    const END = 3000;
    const VIEWPORT = 500;

    /** Where the receiver goes, rounded, for a sender in the given region at the given progress. */
    function target(
        regions: SegmentRegion[],
        key: string,
        sections: SectionTop[],
        progress: number,
        band = 0
    ) {
        const t = resolveSegmentTarget(
            regions,
            END,
            key,
            sections,
            progress,
            band,
            VIEWPORT / END,
            VIEWPORT
        );
        return t === undefined ? undefined : Math.round(t);
    }

    it('places the receiver at the same fraction of a region both sides have', () => {
        const regions = local(['a', 0], ['b', 900], ['c', 1800]);
        const sections = sender(['a', 0], ['b', 0.3], ['c', 0.6]);
        expect(target(regions, 'b', sections, 0.45)).toBe(1350);
    });

    it('has no answer without the sender regions, its progress, or a shared key', () => {
        const regions = local(['a', 0], ['b', 1000]);
        const sections = sender(['a', 0], ['b', 0.5]);
        expect(resolveSegmentTarget(regions, END, 'b', undefined, 0.6)).toBeUndefined();
        expect(resolveSegmentTarget(regions, END, 'b', sections, undefined)).toBeUndefined();
        // The key is not among the sender's regions
        expect(target(regions, 'z', sections, 0.2)).toBeUndefined();
        // Nothing is shared around the sender's region
        expect(
            target(local(['a', 0]), 'z', sender(['y', 0], ['z', 0.3], ['x', 0.6]), 0.4)
        ).toBeUndefined();
    });

    it('without a band, holds at the next shared key while the sender passes a section only it has', () => {
        const regions = local(['a', 0], ['b', 1000], ['c', 2000]);
        const sections = sender(['a', 0], ['x', 0.3], ['b', 0.5], ['c', 0.8]);
        expect(target(regions, 'a', sections, 0.3)).toBe(1000);
        expect([0.3, 0.4, 0.5].map((p) => target(regions, 'x', sections, p))).toEqual([
            1000, 1000, 1000,
        ]);
    });

    it('with a band, creeps through the end of the shared section while the sender passes a section only it has', () => {
        const regions = local(['a', 0], ['b', 1000], ['c', 2000]);
        const sections = sender(['a', 0], ['x', 0.3], ['b', 0.5], ['c', 0.8]);
        // Half a viewport (250 px) of 'a' is given to 'x'
        expect([0, 0.15, 0.3].map((p) => target(regions, 'a', sections, p, 0.5))).toEqual([
            0, 375, 750,
        ]);
        expect([0.3, 0.4, 0.5].map((p) => target(regions, 'x', sections, p, 0.5))).toEqual([
            750, 875, 1000,
        ]);
        expect(target(regions, 'b', sections, 0.5, 0.5)).toBe(1000);
    });

    it('gives at most half of the shared section to the band', () => {
        const regions = local(['a', 0], ['b', 200], ['c', 2000]);
        const sections = sender(['a', 0], ['x', 0.3], ['b', 0.5], ['c', 0.8]);
        expect(target(regions, 'x', sections, 0.3, 0.5)).toBe(100);
    });

    it('without a band, stretches the shared section over a section only the receiver has', () => {
        const regions = local(['a', 0], ['y', 1000], ['b', 1500], ['c', 2000]);
        const sections = sender(['a', 0], ['b', 0.3], ['c', 0.6]);
        expect([0, 0.15, 0.3].map((p) => target(regions, 'a', sections, p))).toEqual([
            0, 750, 1500,
        ]);
    });

    it('passes sections only one side has during each other where both sides have one', () => {
        const regions = local(['a', 0], ['y', 1000], ['b', 1500], ['c', 2000]);
        const sections = sender(['a', 0], ['x', 0.3], ['b', 0.5], ['c', 0.8]);
        expect([0.3, 0.4, 0.5].map((p) => target(regions, 'x', sections, p, 0.5))).toEqual([
            1000, 1250, 1500,
        ]);
    });

    it('with a band, maps back to where it came from when the other side leads', () => {
        // 'x' only exists on the left. Each side's pixels and progress share the same scale here.
        const left = local(['a', 0], ['x', 1000], ['b', 1500], ['c', 2000]);
        const right = local(['a', 0], ['b', 1000], ['c', 2000]);
        const asSender = (regions: SegmentRegion[]) =>
            sender(...regions.map((r): [string, number] => [r.key, r.top / END]));
        const keyAt = (regions: SegmentRegion[], px: number) =>
            regions.filter((r) => r.top <= px).pop()!.key;
        const map = (from: SegmentRegion[], to: SegmentRegion[], px: number, band: number) =>
            resolveSegmentTarget(
                to,
                END,
                keyAt(from, px),
                asSender(from),
                px / END,
                band,
                VIEWPORT / END,
                VIEWPORT
            )!;

        for (const px of [0, 400, 800, 1000, 1200, 1499, 1500, 2500]) {
            const there = map(left, right, px, 0.5);
            expect(map(right, left, there, 0.5)).toBeCloseTo(px, 6);
        }

        // Without a band the held position stands for all of 'x', so it maps back to one point
        expect(map(right, left, map(left, right, 1200, 0), 0)).not.toBeCloseTo(1200, 0);
    });

    it('counts subsections only the sender has as part of their parent', () => {
        const regions = local(['ch1', 0], ['ch2', 1200]);
        const nested = sender(
            ['ch1', 0],
            ['ch1-1', 0.1, 'ch1'],
            ['ch1-2', 0.3, 'ch1'],
            ['ch2', 0.6]
        );
        expect(
            [0.05, 0.3, 0.45].map((p) =>
                target(regions, p < 0.1 ? 'ch1' : p < 0.3 ? 'ch1-1' : 'ch1-2', nested, p, 0.5)
            )
        ).toEqual([100, 600, 900]);

        // The same sections as siblings are sections the receiver lacks
        const siblings = sender(['ch1', 0], ['ch1-1', 0.1], ['ch1-2', 0.3], ['ch2', 0.6]);
        expect(target(regions, 'ch1-2', siblings, 0.45, 0)).toBe(1200);
    });

    it('counts subsections only the receiver has as part of their parent', () => {
        const regions = local(
            ['ch1', 0],
            ['ch1-1', 400, 'ch1'],
            ['ch1-2', 800, 'ch1'],
            ['ch2', 1200]
        );
        const sections = sender(['ch1', 0], ['ch2', 0.6]);
        expect([0, 0.3, 0.6].map((p) => target(regions, 'ch1', sections, p, 0.5))).toEqual([
            0, 600, 1200,
        ]);
    });

    it('spreads the sender\'s leading region over everything before the first shared key here (the "Dryhaven" case)', () => {
        // Sender: an unkeyed prologue, then Chapter 1. Receiver: a cast list with keyed sections of
        // its own before Chapter 1. Matching the two leading regions sent the receiver to its empty
        // leading region at the very top.
        const regions = local(['cast', 0], ['cast/heroes', 1000], ['1', 2000], ['2', 2500]);
        const sections = sender([LEADING_REGION_KEY, 0], ['1', 0.4], ['2', 0.7]);
        expect(target(regions, LEADING_REGION_KEY, sections, 0.1)).toBe(500);
    });

    it('never matches the leading regions of the two documents directly', () => {
        // Both have the sentinel. Before the first shared key there is only the gap, which is
        // exactly the two leading regions here, so they line up anyway.
        const regions = local([LEADING_REGION_KEY, 0], ['chapter-1', 1000], ['chapter-2', 2000]);
        const sections = sender([LEADING_REGION_KEY, 0], ['chapter-1', 0.2], ['chapter-2', 0.6]);
        expect(target(regions, LEADING_REGION_KEY, sections, 0.1)).toBe(500);
    });

    it('does not use the leading region as a bracket for an unmatched intro section (regression)', () => {
        const regions = local([LEADING_REGION_KEY, 0], ['chapter-1', 1000], ['chapter-2', 2000]);
        const sections = sender(
            [LEADING_REGION_KEY, 0],
            ['toc', 0.1],
            ['chapter-1', 0.4],
            ['chapter-2', 0.6]
        );
        expect(target(regions, 'toc', sections, 0.2)).toBe(500);
    });

    it('runs to the document end after the last shared key', () => {
        const regions = local(['a', 0], ['b', 1000], ['notes', 2000]);
        const sections = sender(['a', 0], ['b', 0.3], ['appendix', 0.6]);
        expect([0.6, 0.8, 1].map((p) => target(regions, 'appendix', sections, p, 0.5))).toEqual([
            2000, 2500, 3000,
        ]);
    });

    it('never moves backwards through reordered sections', () => {
        // The receiver has 'b' before 'a'; the sender passes 'x' after 'a'
        const regions = local(['b', 0], ['a', 1000]);
        const sections = sender(['a', 0], ['x', 0.3], ['b', 0.5]);
        const path = [0, 0.1, 0.2, 0.3, 0.4, 0.49].map((p) =>
            target(regions, p < 0.3 ? 'a' : 'x', sections, p, 0.5)!
        );
        for (let i = 1; i < path.length; i++) expect(path[i]).toBeGreaterThanOrEqual(path[i - 1]);
        expect(path[0]).toBe(1000);
    });
});

describe('ApacheAnnotatorSyncController region geometry', () => {
    // jsdom has no layout, so the scroll container and the section tops are stubbed. Each section is
    // given a fixed position in the content; its viewport top follows from the current scrollTop.
    // Positions given to and read from the controller below are sync point positions in the content
    // (see syncPointFraction), not scrollTops.
    const CLIENT_HEIGHT = 500;

    let root: HTMLElement;
    let container: HTMLElement;
    let controller: ApacheAnnotatorSyncController;

    beforeEach(() => {
        container = document.createElement('div');
        root = document.createElement('div');
        container.appendChild(root);
        document.body.appendChild(container);
        controller = new ApacheAnnotatorSyncController(root, () => container);
        // These tests read where a sync puts the viewport right away; the glide is tested below
        controller.glide = false;
    });

    afterEach(() => container.remove());

    /** Lay out one region per key, starting at the given content position. */
    function layout(sections: [string, number][], scrollHeight: number) {
        Object.defineProperty(container, 'scrollHeight', { value: scrollHeight });
        Object.defineProperty(container, 'clientHeight', { value: CLIENT_HEIGHT });
        container.getBoundingClientRect = () => ({ top: 0 }) as DOMRect;

        const regions: SyncRegion[] = sections.map(([key, position], i) => {
            const element = document.createElement('section');
            element.getBoundingClientRect = () =>
                ({ top: position - container.scrollTop }) as DOMRect;
            root.appendChild(element);
            return { key, start: i, end: i + 1, startElement: element };
        });

        const tocRoot = { children: [] } as unknown as TocLevel;
        controller.setDocumentStructure({
            sectionSelector: () => 'section',
            extractKey: () => undefined,
            tocRoot: () => tocRoot,
        });
        const internals = controller as any;
        internals.regions = regions;
        internals.regionsTocRoot = tocRoot;
    }

    /** Where this controller puts its sync point for a sender in the given region and position. */
    function follow(
        key: string,
        sections: SectionTop[],
        scrollProgress: number,
        sectionFraction = 0
    ): number {
        controller.reset();
        controller.scrollToViewportPosition({
            begin: 0,
            fraction: 0,
            scrollProgress,
            sectionKey: key,
            sectionFraction,
            sections,
        });
        return Math.round((controller as any).syncPointAt(container));
    }

    /** The section position this controller reports with its sync point at the given position. */
    function report(syncPoint: number) {
        container.scrollTop = controller.scrollTopForSyncPoint(container, syncPoint);
        const pos = { begin: 0, end: 0, fraction: 0 } as ViewportScrollPosition;
        (controller as any).augmentWithSectionPosition(pos, container);
        return pos;
    }

    it('creeps through the end of the region before while the sender crosses a region this document does not have', () => {
        // The sender has 'x' between 'a' and 'b'; here 'b' follows 'a' directly
        layout(
            [
                ['a', 0],
                ['b', 1000],
                ['c', 2000],
            ],
            3000
        );
        const sections = sender(['a', 0], ['x', 0.3], ['b', 0.5], ['c', 0.8]);

        // The last half viewport (250 px) of 'a' is given to 'x', and 'b' starts where 'x' ends
        expect(follow('a', sections, 0.3, 1)).toBe(750);
        expect([0.3, 0.4, 0.5].map((p) => follow('x', sections, p))).toEqual([750, 875, 1000]);
        expect(follow('b', sections, 0.5, 0)).toBe(1000);
    });

    it('moves through content of its own between the bracketing regions while the sender is in the gap', () => {
        // 'y' exists only here, between 'a' and 'b'
        layout(
            [
                ['a', 0],
                ['y', 1000],
                ['b', 1500],
                ['c', 2000],
            ],
            3000
        );
        const sections = sender(['a', 0], ['x', 0.3], ['b', 0.5], ['c', 0.8]);

        expect([0.3, 0.4, 0.5].map((p) => follow('x', sections, p))).toEqual([1000, 1250, 1500]);
    });

    it('moves at a steady pace across sender regions of very different heights', () => {
        // The sender has a short and a long region of its own before '1'; here an introduction
        // without a key in the sender comes first
        layout(
            [
                ['intro', 0],
                ['1', 1000],
                ['2', 2000],
            ],
            4000
        );
        const sections = sender(
            ['cast', 0],
            ['heroes', 0.01],
            ['lore', 0.1],
            ['1', 0.9],
            ['2', 0.95]
        );

        // Counting regions would cover a third of the way in 'heroes', which is only 1% of the gap
        expect(
            [0.009, 0.45, 0.7].map((p) => follow(p < 0.01 ? 'heroes' : 'lore', sections, p))
        ).toEqual([10, 500, 778]);
    });

    it('does not snap back to a target it already applied after the user has scrolled by hand', () => {
        layout(
            [
                ['a', 0],
                ['b', 1000],
            ],
            3000
        );
        const target: ViewportScrollTarget = {
            begin: 0,
            end: 0,
            fraction: 0,
            scrollProgress: 0.3,
            sectionKey: 'a',
            sectionFraction: 0.5,
            sections: sender(['a', 0], ['b', 1 / 3]),
        };

        controller.reset();
        controller.scrollToViewportPosition(target);
        const applied = container.scrollTop;

        // The user scrolls on; the same target arrives again (a repeated frame), so it is skipped
        container.scrollTop = applied + 100;
        controller.scrollToViewportPosition(target);
        expect(container.scrollTop).toBe(applied + 100);

        // A target that differs from the last one is applied
        controller.scrollToViewportPosition({ ...target, sectionFraction: 0.9 });
        expect(container.scrollTop).not.toBe(applied + 100);
    });

    it('reports where every region starts as a fraction of the content height', () => {
        layout(
            [
                ['a', 0],
                ['b', 1000],
                ['c', 2000],
                ['d', 2800],
            ],
            3000
        );

        const tops = report(1250).sections!;
        expect(tops.map((s) => s.key)).toEqual(['a', 'b', 'c', 'd']);
        [0, 1000 / 3000, 2000 / 3000, 2800 / 3000].forEach((top, i) =>
            expect(tops[i].top).toBeCloseTo(top, 9)
        );
    });

    it('ends the last region at the bottom of the content, on both sides', () => {
        layout(
            [
                ['a', 0],
                ['b', 1000],
                ['c', 2000],
            ],
            3000
        );
        const sections = sender(['a', 0], ['b', 1 / 3], ['c', 2 / 3]);

        // The sender's fraction reaches 1 at its bottom, and the receiver's bottom is at fraction 1
        expect(report(3000).sectionFraction).toBe(1);
        expect(report(3000).sectionKey).toBe('c');
        expect(report(2500).sectionFraction).toBeCloseTo(0.5, 9);
        expect(follow('c', sections, 1, 1)).toBe(3000);
        expect(container.scrollTop).toBe(2500);
        // Entering the last region continues from the end of the region before, with no jump
        expect(follow('b', sections, 2 / 3, 1)).toBe(2000);
        expect(follow('c', sections, 2 / 3, 0)).toBe(2000);
    });

    it('reaches a region that starts less than a viewport height above the end of the content', () => {
        layout(
            [
                ['a', 0],
                ['b', 1000],
                ['c', 2800],
            ],
            3000
        );
        const sections = sender(['a', 0], ['b', 1 / 3], ['c', 2800 / 3000]);

        expect(report(2900).sectionKey).toBe('c');
        expect(report(2900).sectionFraction).toBeCloseTo(0.5, 9);
        expect(follow('c', sections, 2900 / 3000, 0.5)).toBe(2900);
    });

    it('scrolls through its own sections before the first shared one while the sender is in its leading region', () => {
        // The sender has an unkeyed prologue before '1'; here a cast list with keys of its own
        // comes first
        layout(
            [
                ['cast', 0],
                ['heroes', 1000],
                ['1', 3000],
                ['2', 4000],
            ],
            6000
        );
        const sections = sender([LEADING_REGION_KEY, 0], ['1', 0.4], ['2', 0.7]);

        expect([0, 0.2, 0.4].map((p) => follow(LEADING_REGION_KEY, sections, p))).toEqual([
            0, 1500, 3000,
        ]);
        expect(follow('1', sections, 0.4, 0)).toBe(3000);
    });

    it('waits at the first shared section when it has nothing before it', () => {
        layout(
            [
                ['1', 0],
                ['2', 1000],
            ],
            3000
        );
        const sections = sender([LEADING_REGION_KEY, 0], ['1', 0.4], ['2', 0.7]);

        expect([0, 0.2, 0.4].map((p) => follow(LEADING_REGION_KEY, sections, p))).toEqual([
            0, 0, 0,
        ]);
    });

    it('scrolls through its own sections after the last shared one while the sender is past it', () => {
        layout(
            [
                ['a', 0],
                ['b', 1000],
                ['notes', 2000],
            ],
            4000
        );
        const sections = sender(['a', 0], ['b', 0.3], ['appendix', 0.6]);

        // From the end of 'b' to the bottom of the content
        expect([0.6, 0.8, 1].map((p) => follow('appendix', sections, p))).toEqual([
            2000, 3000, 4000,
        ]);
    });

    describe('glide', () => {
        let frames: FrameRequestCallback[];

        beforeEach(() => {
            frames = [];
            vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
                frames.push(callback);
                return frames.length;
            });
            vi.stubGlobal('cancelAnimationFrame', () => {
                frames = [];
            });
            controller.glide = true;
            layout(
                [
                    ['a', 0],
                    ['b', 1000],
                    ['c', 2000],
                ],
                3000
            );
        });

        afterEach(() => vi.unstubAllGlobals());

        /** Run the pending animation frames, one frame of 16 ms each, until none are left. */
        function runFrames(limit = 1000): number {
            let n = 0;
            for (let time = 0; frames.length && n < limit; n++, time += 16) {
                const pending = frames;
                frames = [];
                for (const callback of pending) callback(time);
            }
            return n;
        }

        const sections = sender(['a', 0], ['b', 1 / 3], ['c', 2 / 3]);
        const towards = (key: string, scrollProgress: number) =>
            controller.scrollToViewportPosition({
                begin: 0,
                fraction: 0,
                scrollProgress,
                sectionKey: key,
                sections,
            });

        it('moves to a distant position over several frames', () => {
            towards('b', 0.5);
            expect(container.scrollTop).toBe(0);

            const n = runFrames();
            expect(n).toBeGreaterThan(5);
            expect(Math.round((controller as any).syncPointAt(container))).toBe(1500);
        });

        it('moves to a close position at once', () => {
            towards('a', 0.02);
            expect(frames).toEqual([]);
            expect(Math.round((controller as any).syncPointAt(container))).toBe(60);
        });

        it('counts the positions it moves through as its own, and stops when something else moves the viewport', () => {
            towards('b', 0.5);
            runFrames(3);
            const reached = container.scrollTop;
            expect(reached).toBeGreaterThan(0);
            expect(controller.isApplyingViewportSync()).toBe(true);

            // The user scrolls the pane while it glides: they take over
            container.scrollTop = reached + 7;
            expect(controller.isApplyingViewportSync()).toBe(false);
            runFrames();
            expect(container.scrollTop).toBe(reached + 7);
        });
    });

    it('forgets the position it synced to once the viewport has been anywhere else', () => {
        // Moved to the top by a sync, scrolled down by the user, and back up to the top: the last
        // move is the user's, not an echo of the sync
        layout(
            [
                ['a', 0],
                ['b', 1000],
            ],
            3000
        );
        controller.scrollToViewportPosition({
            begin: 0,
            fraction: 0,
            scrollProgress: 0,
            sectionKey: 'a',
            sections: sender(['a', 0], ['b', 1 / 3]),
        });
        expect(controller.isApplyingViewportSync()).toBe(true);

        container.scrollTop = 300;
        expect(controller.isApplyingViewportSync()).toBe(false);
        container.scrollTop = 0;
        expect(controller.isApplyingViewportSync()).toBe(false);
    });
});
