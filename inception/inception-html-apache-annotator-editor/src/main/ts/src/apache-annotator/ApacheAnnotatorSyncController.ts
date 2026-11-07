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
import {
    type AnnotationEditor,
    type SectionTop,
    type TocLevel,
    type ViewportScrollPosition,
    type ViewportScrollTarget,
    type ViewportSyncPeer,
    generateTOCKeyIndex,
    offsetToRange,
    calculateStartOffset,
    calculateEndOffset,
} from '@inception-project/inception-js-api';
import { removeClassFromAncestors } from './Utilities';

/**
 * Optional document-structure inputs the controller uses for by-key scroll sync. All are read
 * lazily so the controller sees the current document's structure without being reconstructed.
 * When absent (or when they yield no key), the controller uses only offset/fraction anchoring,
 * so behaviour is identical to an editor with no structure.
 */
export interface SyncStructureAccessors {
    /** Selector matching section elements (empty string when the document has no structure). */
    sectionSelector: () => string;
    /** The structural key for a section element, or undefined when it has none. */
    extractKey: (section: Element) => string | undefined;
    /** The current document's TOC tree, or undefined before a document is loaded. */
    tocRoot: () => TocLevel | undefined;
}

/**
 * The document as a flat list of non-overlapping "scroll-sync regions" for by-key sync.
 *
 * The document is partitioned by the START offsets of its keyed sections: a region runs from one
 * section start to the NEXT section start (never back up to an enclosing section), so regions never
 * nest or overlap and every position belongs to exactly one region -- the non-overlap is what keeps
 * the intra-region fraction continuous across section boundaries.
 *
 * Each region is identified by the key of the section that opens it, so two documents sharing a
 * structure produce the same ordered region keys and align region-by-region. The leading region
 * (document start up to the first keyed section) has no opening section and gets a fixed sentinel
 * key. The sentinel is not a key: every document has it, whatever its leading region contains, so
 * it never matches the other document's leading region (see resolveSegmentTarget).
 */
export const LEADING_REGION_KEY = '@@sync-leading-region';

export interface SyncRegion {
    key: string;
    /** Character-offset span of the region: [start, end). */
    start: number;
    end: number;
    /**
     * The section element that opens this region, or undefined for the leading region (which starts
     * at the document top). Its pixel top defines the region boundary the fraction is measured
     * against; the region ends where the next region's element begins.
     */
    startElement?: Element;
    /**
     * The key of the nearest enclosing section that is itself a region, or undefined when there is
     * none. Regions do not nest, but the sections that open them do.
     */
    parentKey?: string;
}

/**
 * Build the sorted, non-overlapping regions from a TOC. Region boundaries are the keyed sections'
 * start offsets (via {@code calculateStartOffset}); {@code docEnd} closes the last region. Sections
 * without a key are ignored (they do not partition -- their content belongs to the enclosing
 * region). Duplicate/shared keys keep their first occurrence in document order, matching
 * {@link generateTOCKeyIndex}. Returns regions in ascending start order, always beginning with the
 * leading region [0, firstSectionStart) keyed {@link LEADING_REGION_KEY}.
 */
export function buildSyncRegions(root: Element, tocRoot: TocLevel, docEnd: number): SyncRegion[] {
    const byKey = generateTOCKeyIndex(tocRoot);

    type Boundary = { key: string; start: number; element?: Element };
    const starts: Boundary[] = [];
    for (const key of Object.keys(byKey)) {
        const el = byKey[key].element;
        if (!el) continue;
        const start = calculateStartOffset(root, el);
        if (start < 0) continue;
        starts.push({ key, start, element: el });
    }

    starts.sort((a, b) => a.start - b.start);

    // Boundary list: leading sentinel at 0, then each section start (deduped by offset). Two sections
    // sharing a start offset would make a zero-width region; keep the first key at that offset.
    const boundaries: Boundary[] = [{ key: LEADING_REGION_KEY, start: 0 }];
    for (const s of starts) {
        if (s.start <= 0) continue; // a section at offset 0 replaces the leading region below
        const prev = boundaries[boundaries.length - 1];
        if (s.start === prev.start) continue; // same offset -> first key wins
        boundaries.push(s);
    }

    // If a keyed section starts exactly at 0, it owns the leading region instead of the sentinel.
    const zeroStart = starts.find((s) => s.start === 0);
    if (zeroStart) boundaries[0] = zeroStart;

    const regions: SyncRegion[] = [];
    for (let i = 0; i < boundaries.length; i++) {
        const start = boundaries[i].start;
        const end = i + 1 < boundaries.length ? boundaries[i + 1].start : docEnd;
        if (end <= start) continue; // drop empty/inverted regions (e.g. docEnd before last start)
        regions.push({
            key: boundaries[i].key,
            start,
            end,
            startElement: boundaries[i].element,
        });
    }

    // The nearest enclosing section that opens a region. byKey holds the first section per key, so
    // an ancestor only counts if it is that section, not a later one sharing its key.
    const regionKeys = new Set(regions.map((r) => r.key));
    for (const region of regions) {
        for (let p = byKey[region.key]?.parent; p; p = p.parent) {
            if (p.key !== undefined && regionKeys.has(p.key) && byKey[p.key] === p) {
                region.parentKey = p.key;
                break;
            }
        }
    }

    return regions;
}

/** The default {@code band} of {@link resolveSegmentTarget}, in viewport heights. */
export const DEFAULT_SEGMENT_BAND = 0.5;

/** A receiver region for {@link resolveSegmentTarget}: its key, parent and pixel top. */
export type SegmentRegion = { key: string; parentKey?: string; top: number };

/**
 * Where the receiver's sync point goes for a by-key sync position, aligning the two documents
 * segment by segment between the keys they share, or undefined when there is no shared key around
 * the sender's position (the caller then falls back).
 *
 * Between a shared key K and the next shared key, each side's content splits into two parts:
 * <ul>
 * <li>the <b>K part</b>: K's own region plus the regions nested in K that the other side does not
 * have (subsections only one side has), and</li>
 * <li>the <b>gap</b>: regions the other side does not have that are not nested in K (sections only
 * one side has).</li>
 * </ul>
 * The sender's K part maps linearly onto the receiver's K part, and the sender's gap onto the
 * receiver's gap. Where only one side has a gap, the other side gives it the last {@code band}
 * viewport heights of its K part, at most half of it, and creeps through that band while the gap
 * is passed. The band is measured in the viewport of the side that gives it, whichever side leads
 * (which is why the sender reports its viewport height as {@code senderViewportFraction}), so the
 * mapping is the same in both directions and a change of the leading side never makes the other
 * side jump. Before the first shared key there is only a gap, from the document start, and after
 * the last one the gap runs to the document end.
 * <p>
 * Without a band, the side without a gap holds at the next shared key while the other passes its
 * gap, or stretches its K part over the gap when it is the receiver. That does not map back: a held
 * position corresponds to the whole gap on the other side, so the other side jumps when the
 * leading side changes.
 * <p>
 * {@link LEADING_REGION_KEY} is never a shared key: every document with a leading region has the
 * sentinel, whatever its leading region contains, so it would be trivially "shared" even when the
 * two documents' leading content is unrelated. Two leading regions that do correspond, each
 * directly followed by the same first shared key, still line up: before the first shared key there
 * is only the gap, which is then exactly the two leading regions.
 *
 * @param regions
 *            the receiver's regions in document order, with tops in content pixels
 * @param end
 *            the end of the receiver's sync point range in content pixels
 * @param band
 *            the band in viewport heights, or 0 to hold and stretch instead
 * @param senderViewportFraction
 *            the sender's viewport height in its scroll progress
 * @param receiverViewportHeight
 *            the receiver's viewport height in content pixels
 */
export function resolveSegmentTarget(
    regions: SegmentRegion[],
    end: number,
    sectionKey: string,
    sections: SectionTop[] | undefined,
    scrollProgress: number | undefined,
    band = DEFAULT_SEGMENT_BAND,
    senderViewportFraction?: number,
    receiverViewportHeight = 0
): number | undefined {
    if (!sections || scrollProgress === undefined) return undefined;

    const s = sections.findIndex((x) => x.key === sectionKey);
    if (s < 0) return undefined;

    const localIndex = new Map(regions.map((r, i) => [r.key, i]));
    const senderKeys = new Set(sections.map((x) => x.key));
    senderKeys.delete(LEADING_REGION_KEY);
    const shared = (key: string) => key !== LEADING_REGION_KEY && localIndex.has(key);

    const senderParents = new Map(sections.map((x) => [x.key, x.parent]));
    const localParents = new Map(regions.map((r) => [r.key, r.parentKey]));
    const nestedUnder = (
        parents: Map<string, string | undefined>,
        key: string,
        ancestor: string
    ) => {
        for (let p = parents.get(key); p !== undefined; p = parents.get(p)) {
            if (p === ancestor) return true;
        }
        return false;
    };

    // Sender, in scroll progress: K part [a, b), gap [b, c)
    const senderTop = (i: number) => (i < sections.length ? sections[i].top : 1);
    let k = s;
    while (k >= 0 && !shared(sections[k].key)) k--;
    let kEnd = k + 1;
    if (k >= 0) {
        while (
            kEnd < sections.length &&
            !shared(sections[kEnd].key) &&
            nestedUnder(senderParents, sections[kEnd].key, sections[k].key)
        ) {
            kEnd++;
        }
    }
    let l = kEnd;
    while (l < sections.length && !shared(sections[l].key)) l++;
    if (k < 0 && l >= sections.length) return undefined;

    const a = k >= 0 ? sections[k].top : 0;
    const b = k >= 0 ? senderTop(kEnd) : 0;
    const c = senderTop(l);

    // Receiver, in content pixels: K part [ra, rb), gap [rb, rc). The gap ends at the receiver's
    // next region the sender also has; with reordered sections that need not be the sender's next
    // shared key, but it is never above the K part, so the receiver never runs backwards.
    const localTop = (j: number) => (j < regions.length ? regions[j].top : end);
    const rk = k >= 0 ? localIndex.get(sections[k].key)! : -1;
    let rkEnd = rk + 1;
    if (rk >= 0) {
        while (
            rkEnd < regions.length &&
            !senderKeys.has(regions[rkEnd].key) &&
            nestedUnder(localParents, regions[rkEnd].key, regions[rk].key)
        ) {
            rkEnd++;
        }
    }
    let rl = rkEnd;
    while (rl < regions.length && !senderKeys.has(regions[rl].key)) rl++;

    const ra = rk >= 0 ? regions[rk].top : 0;
    const rc = localTop(rl);
    const rkPartEnd = rk >= 0 ? localTop(rkEnd) : 0;

    const lerp = (from: number, to: number, rFrom: number, rTo: number) =>
        to > from
            ? rFrom +
              Math.min(1, Math.max(0, (scrollProgress - from) / (to - from))) * (rTo - rFrom)
            : rFrom;

    const senderGap = c > b;
    const receiverGap = rc > rkPartEnd;
    const inKPart = k >= 0 && s < kEnd;

    if (band > 0 && k >= 0 && senderGap && !receiverGap && receiverViewportHeight > 0) {
        // The receiver gives the end of its K part to the sender's gap
        const split = rkPartEnd - Math.min(band * receiverViewportHeight, (rkPartEnd - ra) / 2);
        return inKPart ? lerp(a, b, ra, split) : lerp(b, c, split, rkPartEnd);
    }

    if (band > 0 && k >= 0 && !senderGap && receiverGap && senderViewportFraction) {
        // The sender gave the end of its K part to the receiver's gap: the inverse of the above
        const split = b - Math.min(band * senderViewportFraction, (b - a) / 2);
        return scrollProgress < split
            ? lerp(a, split, ra, rkPartEnd)
            : lerp(split, b, rkPartEnd, rc);
    }

    // No sender gap to pass the receiver's gap during: stretch the K part over it
    const rb = senderGap ? rkPartEnd : rc;
    if (inKPart) return lerp(a, b, ra, rb);
    return lerp(b, c, rb, rc);
}

// CSS `display` values whose boxes are block-level; an element with one of these owns a
// distinct block in the flow and so can anchor a text offset (a box whose height is a
// meaningful denominator for the intra-anchor scroll fraction).
const ANCHOR_DISPLAY_CATEGORIES = new Set([
    'block',
    'flex',
    'grid',
    'table',
    'table-row',
    'list-item',
]);

// Our own overlay elements: they sit above the content at the viewport top but carry no document
// offsets, so scroll-sync anchoring must skip them and look at the content behind them instead.
const OVERLAY_SELECTOR =
    '.iaa-section-control, .iaa-vertical-marker-focus, .iaa-ping-marker,' +
    ' iaa-visible-annotations-panel, iaa-visible-annotations-panel-spacer';

/**
 * How long the two transitions of the sync point are, in viewport heights of scrolling: from the
 * viewport top to its middle at the start of the document, and from the middle to the bottom at the
 * end. Each must be greater than 0.
 */
export type SyncPointRamps = { start: number; end: number };

export const DEFAULT_SYNC_POINT_RAMPS: SyncPointRamps = { start: 0.5, end: 0.5 };

/**
 * The two ramps in pixels of scrolling. When together they are longer than the scroll range, both
 * shrink in proportion so that they meet, and the sync point moves from top to bottom across the
 * whole range.
 */
function rampLengths(
    maxScroll: number,
    viewportHeight: number,
    ramps: SyncPointRamps
): { start: number; end: number } {
    const start = ramps.start * viewportHeight;
    const end = ramps.end * viewportHeight;
    const scale = Math.min(1, maxScroll / (start + end));
    return { start: start * scale, end: end * scale };
}

/**
 * Where the sync point lies in the viewport, as a fraction of the viewport height, when the
 * container is scrolled to {@code scrollTop}: at the top at the start of the document, in the middle
 * once {@code ramps.start} viewport heights have been scrolled, and at the bottom at the end, having
 * left the middle {@code ramps.end} viewport heights before it, moving linearly in between.
 *
 * The sync point, {@code scrollTop + fraction * viewportHeight}, thereby runs from the top of the
 * content to its bottom as the viewport scrolls from one end to the other, and only ever forward,
 * so every position in the document can be synced and {@link scrollTopForSyncPointAt} can invert it.
 * The middle is where the reader is most likely looking; the ramps let the ends of the document,
 * which the middle of the viewport never reaches, still be synced.
 */
export function syncPointFraction(
    scrollTop: number,
    maxScroll: number,
    viewportHeight: number,
    ramps: SyncPointRamps = DEFAULT_SYNC_POINT_RAMPS
): number {
    if (maxScroll <= 0 || viewportHeight <= 0) return 0;

    const ramp = rampLengths(maxScroll, viewportHeight, ramps);
    const t = Math.min(maxScroll, Math.max(0, scrollTop));
    return (
        0.5 * Math.min(1, t / ramp.start) +
        0.5 * Math.max(0, (t - (maxScroll - ramp.end)) / ramp.end)
    );
}

/**
 * The scrollTop at which the sync point lies at {@code syncPoint} in the scrolled content: the
 * inverse of {@code scrollTop + syncPointFraction(scrollTop, ...) * viewportHeight}. The sync point
 * is piecewise linear in scrollTop (ramp, plateau, ramp), so each piece inverts directly. Values
 * outside the content are clamped to its ends.
 */
export function scrollTopForSyncPointAt(
    syncPoint: number,
    maxScroll: number,
    viewportHeight: number,
    ramps: SyncPointRamps = DEFAULT_SYNC_POINT_RAMPS
): number {
    if (maxScroll <= 0) return 0;
    if (viewportHeight <= 0) return Math.min(maxScroll, Math.max(0, syncPoint));

    const ramp = rampLengths(maxScroll, viewportHeight, ramps);
    const half = 0.5 * viewportHeight;
    // On a ramp the sync point moves this much per pixel scrolled
    const startSlope = 1 + half / ramp.start;
    const endSlope = 1 + half / ramp.end;
    const p = Math.min(maxScroll + viewportHeight, Math.max(0, syncPoint));
    const plateauStart = ramp.start + half; // the sync point at scrollTop = ramp.start
    const plateauEnd = maxScroll - ramp.end + half; // ... at maxScroll - ramp.end

    if (p <= plateauStart) return p / startSlope;
    if (p <= plateauEnd) return p - half;
    return maxScroll - ramp.end + (p - plateauEnd) / endSlope;
}

/**
 * Fraction of the sync point's range over which to blend toward overall progress at each end.
 */
const EXTREME_BLEND_BAND = 0.15;

/**
 * Blend an offset-anchored sync point position toward the sender's overall progress near the
 * extremes. {@code range} is the length of the range the position moves in, so that progress 0
 * maps to 0 and progress 1 to {@code range}.
 *
 * Offset-anchoring lands short of this editor's own extreme when the two editors have different
 * amounts of content beyond the shared block, while a pure progress mapping drifts in the interior.
 * Weighting toward progress only within a band next to each extreme makes the extremes exact and
 * keeps the interior offset-accurate, without a snap between the two.
 */
export function blendTowardScrollProgress(
    anchoredTop: number,
    scrollProgress: number | undefined,
    range: number
): number {
    if (scrollProgress === undefined || range <= 0) return anchoredTop;

    const progressTop = scrollProgress * range;
    const p = scrollProgress;
    // 1 at an extreme -> 0 by EXTREME_BLEND_BAND in from it.
    const nearness = Math.max(0, 1 - Math.min(p, 1 - p) / EXTREME_BLEND_BAND);
    return nearness * progressTop + (1 - nearness) * anchoredTop;
}

/**
 * Whether two {@code scrollProgress} values would drive {@link blendTowardScrollProgress} to the
 * same place, for the per-frame dedup in {@link ApacheAnnotatorSyncController}. Uses the same 0.01
 * tolerance as the fraction comparison there.
 *
 * A present value never matches an absent one: dropping progress switches the receiver from the
 * blended target back to the bare offset anchor, which is a real move, not a no-op.
 */
export function sameScrollProgress(a: number | undefined, b: number | undefined): boolean {
    if (a === undefined || b === undefined) return a === b;
    return Math.abs(a - b) < 0.01;
}

/**
 * Editor-to-editor viewport synchronization for the Apache Annotator visualizer. Implements the
 * {@code AnnotationEditor} viewport-sync protocol: {@link getViewportScrollPosition} reports the
 * document offset currently at the sync point (plus how far the sync point is into it), and
 * {@link scrollToViewportPosition} places a given offset+fraction back at the sync point. Where in
 * the viewport the sync point lies depends on the scroll position, see {@link syncPointFraction}.
 * Interpolating in character space rather than pixels keeps two editors in sync even when they wrap
 * the same text to different heights.
 *
 * The controller depends only on the editor root and a lazily-read scroll container (assigned after
 * the visualizer is constructed, hence the getter), and touches only DOM geometry.
 */
export class ApacheAnnotatorSyncController {
    private readonly root: Element;
    private readonly getScrollContainer: () => Element | undefined;

    /**
     * Last viewport position applied by {@link scrollToViewportPosition}, for per-frame dedup.
     *
     * {@code scrollProgress} is part of the key because it moves the target near an extreme even
     * when begin/fraction agree. Dedup never crosses the by-key ({@link scrollToRegion}) and offset
     * ({@link scrollToOffset}) paths, which compute different pixel targets from the same position:
     * each guard also checks that the last apply came from its own path.
     */
    private lastAppliedViewportScrollPosition:
        | {
              begin: number;
              fraction: number;
              scrollProgress: number | undefined;
              sectionKey?: string;
              sectionFraction?: number;
          }
        | undefined = undefined;

    /** How long the sync point's transitions at the document ends are, see {@link syncPointFraction}. */
    syncPointRamps: SyncPointRamps = DEFAULT_SYNC_POINT_RAMPS;

    /**
     * How many viewport heights of the shared section before it a section only the other side has
     * is given, see {@link resolveSegmentTarget}. 0 to hold and stretch instead.
     */
    segmentBand = DEFAULT_SEGMENT_BAND;

    /**
     * Move to a synced position over several frames instead of at once, when it is at least
     * {@link glideThreshold} viewport heights away or a glide is already under way. Each frame
     * closes the remaining distance with time constant {@link glideTimeConstantMs}. Smooths the
     * jumps that remain where the two documents order their sections differently.
     */
    glide = true;
    glideTimeConstantMs = 200;
    glideThreshold = 0.25;

    private glideTarget?: number;
    private glideFrame?: number;
    private glideLastTime?: number;

    /**
     * The scrollTop this controller last set for a sync, while the viewport is still there; see
     * {@link isApplyingViewportSync}.
     */
    private appliedScrollTop?: number;

    /** The hub this controller has registered its editor with, and the id it used. */
    private hub?: ViewportSyncPeer;
    private hubPeerId?: string;

    /** Document-structure inputs for by-key sync; undefined until {@link setDocumentStructure}. */
    private structure?: SyncStructureAccessors;

    /**
     * Memoized flat scroll-sync regions for the current tocRoot, so per-scroll region lookup does not
     * re-walk the TOC. Rebuilt lazily when the tocRoot identity changes (e.g. a new document is
     * loaded), which is why the tocRoot it was built from is remembered alongside it.
     */
    private regions?: SyncRegion[];
    private regionsTocRoot?: TocLevel;

    /**
     * Where each region start element was last seen while laid out, as a position in the scrolled
     * content. Inside a secluded section the live position is wrong: nested secluded sections are
     * display: none, so they have no box and everything after them in that section moves up. Such
     * elements use the position recorded here instead (see {@link regionTopScrollPos}). Content
     * that changes later (e.g. annotations rendering) can make it drift a little, which is accepted.
     */
    private recordedTops = new WeakMap<Element, number>();

    constructor(root: Element, getScrollContainer: () => Element | undefined) {
        this.root = root;
        this.getScrollContainer = getScrollContainer;
    }

    /**
     * Provide (or replace) the document-structure inputs used for by-key scroll sync. Called by the
     * editor once the document outline exists. Without this, the controller uses offset anchoring
     * only.
     */
    setDocumentStructure(structure: SyncStructureAccessors): void {
        this.structure = structure;
        // Drop any memoized regions; they will be rebuilt lazily from the new structure's tocRoot.
        this.regions = undefined;
        this.regionsTocRoot = undefined;
        this.recordLayout();
    }

    /**
     * Record where the region start elements are, for those inside a secluded section to use
     * later. Call while the document is fully laid out: the visualizer calls it right after lifting
     * the seclusion and materializing the section sizes, before the sections are secluded again.
     * Elements already inside a secluded section keep what was recorded for them before.
     */
    recordLayout(): void {
        const container = this.getScrollContainer();
        const regions = this.syncRegions();
        if (!container || !regions) return;

        const containerTop = container.getBoundingClientRect().top;
        for (const region of regions) this.regionTopScrollPos(container, region, containerTop);
    }

    /**
     * Register {@code aEditor} with the scroll-sync {@code aHub} under {@code aId}, scoped to the
     * editor's current scroll container. Call only once the viewport exists. Re-registers if called
     * again (e.g. after the container is rebuilt).
     */
    connectToHub(aHub: ViewportSyncPeer, aId: string, aEditor: AnnotationEditor): void {
        this.hub = aHub;
        this.hubPeerId = aId;
        aHub.register(aId, aEditor, this.getScrollContainer() ?? undefined);
    }

    /** Remove the editor from the hub it was registered with via {@link connectToHub}. */
    disconnectFromHub(): void {
        this.stopGlide();
        if (this.hub && this.hubPeerId) {
            this.hub.unregister(this.hubPeerId);
        }
        this.hub = undefined;
        this.hubPeerId = undefined;
    }

    /**
     * How far below the top of the scroll viewport the sync point lies, in pixels, when the container
     * is scrolled to {@code scrollTop}. The sync point is where this controller measures the position
     * it reports ({@link getViewportScrollPosition}) and where it places a position it receives
     * ({@link scrollToViewportPosition}). See {@link syncPointFraction} for the rule.
     *
     * {@link scrollTopForSyncPoint} must stay the inverse of this.
     */
    syncPointOffset(container: Element, scrollTop = container.scrollTop): number {
        const viewportHeight = container.clientHeight;
        const maxScroll = Math.max(0, container.scrollHeight - viewportHeight);
        return (
            syncPointFraction(scrollTop, maxScroll, viewportHeight, this.syncPointRamps) *
            viewportHeight
        );
    }

    /**
     * The sync point as a position in the scrolled content (0 = content top) when the container is
     * scrolled to {@code scrollTop}.
     */
    private syncPointAt(container: Element, scrollTop = container.scrollTop): number {
        return scrollTop + this.syncPointOffset(container, scrollTop);
    }

    /**
     * Where the sync point lies when scrolled to the bottom: the end of the range it moves in, which
     * starts at 0. With the ramps of {@link syncPointFraction} that is the bottom of the content.
     */
    private syncPointEnd(container: Element): number {
        return this.syncPointAt(
            container,
            Math.max(0, container.scrollHeight - container.clientHeight)
        );
    }

    /**
     * The scrollTop at which the sync point lies at {@code syncPoint} in the scrolled content: the
     * inverse of {@link syncPointAt}.
     */
    scrollTopForSyncPoint(container: Element, syncPoint: number): number {
        const viewportHeight = container.clientHeight;
        const maxScroll = Math.max(0, container.scrollHeight - viewportHeight);
        return scrollTopForSyncPointAt(syncPoint, maxScroll, viewportHeight, this.syncPointRamps);
    }

    /**
     * @return the scroll position of the block element currently at the sync point as document
     * offsets plus the fraction of it that lies above the sync point.
     * @see {@link scrollToViewportPosition}.
     */
    getViewportScrollPosition(): ViewportScrollPosition | null {
        const container = this.getScrollContainer();
        if (!container) return null;

        const containerRect = container.getBoundingClientRect();
        const seamY = containerRect.top + this.syncPointOffset(container);
        const anchor = this.findAnchorAtSeam(containerRect, seamY);
        if (!anchor) return null;

        const anchorRect = anchor.getBoundingClientRect();
        const begin = calculateStartOffset(this.root, anchor);
        const end = calculateEndOffset(this.root, anchor);
        const fraction =
            anchorRect.height > 0
                ? Math.min(1, Math.max(0, (seamY - anchorRect.top) / anchorRect.height))
                : 0;

        // Overall progress lets the receiver blend toward its own extreme (see
        // ViewportScrollPosition.scrollProgress)
        const syncPointEnd = this.syncPointEnd(container);
        const scrollProgress = syncPointEnd > 0 ? this.syncPointAt(container) / syncPointEnd : 0;

        const pos: ViewportScrollPosition = { begin, end, fraction, scrollProgress };

        // Attaches a region key and fraction if the sync point is in a keyed region; otherwise the
        // receiver falls back to offset anchoring
        this.augmentWithSectionPosition(pos, container);

        return pos;
    }

    /**
     * Attach {@code sectionKey} / {@code sectionFraction} to {@code pos} for by-key sync, using the
     * flat scroll-sync region containing the sync point. The offset fields are left untouched.
     *
     * The fraction is in pixel space, not character space: a heading is few characters but many
     * pixels tall, so a character fraction would advance unevenly against a smooth scroll.
     */
    private augmentWithSectionPosition(pos: ViewportScrollPosition, container: Element): void {
        const regions = this.syncRegions();
        if (!regions) return; // no structure/outline -> offset anchoring only

        const maxScroll = Math.max(0, container.scrollHeight - container.clientHeight);
        if (maxScroll <= 0) return; // nothing to scroll, nothing to follow

        // The sync point as a position in the scrolled content (0 = content top). Region tops are in
        // the same coordinate (see regionSpans), so bracketing is a direct comparison.
        const syncPoint = this.syncPointAt(container);
        const syncPointEnd = this.syncPointEnd(container);
        const spans = this.regionSpans(container, regions);

        for (let i = 0; i < regions.length; i++) {
            const { top, bottom } = spans[i];
            if (syncPoint < top) break; // regions are sorted; nothing earlier can match
            // Bracket the sync point; the last region also catches the sync point at the very end.
            if (syncPoint < bottom || i === regions.length - 1) {
                const height = bottom - top;
                if (height <= 0) return;
                pos.sectionKey = regions[i].key;
                pos.sectionFraction = Math.min(1, Math.max(0, (syncPoint - top) / height));
                // In the unit of scrollProgress, so the receiver can tell how far the sync point
                // has got between any two of them
                pos.sections = regions.map((region, j) => ({
                    key: region.key,
                    top: spans[j].top / syncPointEnd,
                    parent: region.parentKey,
                }));
                pos.viewportFraction = container.clientHeight / syncPointEnd;
                return;
            }
        }
    }

    /**
     * The regions' tops and bottoms as positions in the scrolled content, within the range the
     * sync point moves in. Shared by the producer's region lookup and the receiver's placement so
     * both sides agree on region boundaries exactly.
     *
     * The sync point reaches the bottom of the content when scrolled to the bottom (see
     * {@link syncPointFraction}), so the last region ends there: its fraction reaches 1 exactly when
     * the sender reaches its bottom, and placing at fraction 1 takes the receiver to its own bottom.
     */
    private regionSpans(
        container: Element,
        regions: SyncRegion[]
    ): { top: number; bottom: number }[] {
        const end = this.syncPointEnd(container);
        const containerTop = container.getBoundingClientRect().top;
        const tops = regions.map((region) =>
            Math.min(this.regionTopScrollPos(container, region, containerTop), end)
        );
        return tops.map((top, i) => ({
            top,
            bottom: i + 1 < tops.length ? tops[i + 1] : end,
        }));
    }

    /**
     * A region's top as a position in the scrolled content (0 = content top). For the leading region
     * (no start element) that is 0; otherwise it is the section element's top: measured where it can
     * be, else as recorded (see {@link recordedTops}). Unclamped; see {@link regionSpans}.
     */
    private regionTopScrollPos(
        container: Element,
        region: SyncRegion,
        containerTop = container.getBoundingClientRect().top
    ): number {
        const element = region.startElement;
        if (!element) return 0;

        if (this.isInSecludedSection(element)) {
            const recorded = this.recordedTops.get(element);
            // Nothing recorded (yet): the live position is the best there is
            if (recorded !== undefined) return recorded;
            return container.scrollTop + (this.elementTop(element) - containerTop);
        }

        const top = container.scrollTop + (this.elementTop(element) - containerTop);
        this.recordedTops.set(element, top);
        return top;
    }

    /**
     * Whether a secluded section encloses {@code element}, which then cannot be measured live. A
     * secluded section of its own is fine: it is only visibility: hidden and keeps its box, and its
     * materialized minimum height keeps everything after it in place.
     */
    private isInSecludedSection(element: Element): boolean {
        const secluded = element.parentElement?.closest('.iaa-secluded');
        return !!secluded && this.root.contains(secluded);
    }

    scrollToViewportPosition(pos: ViewportScrollTarget): void {
        const container = this.getScrollContainer();
        if (!container) return;

        // By-key sync: if the source tagged a region key this document also has, scroll
        // region-fractionally (the inverse of the producer). Otherwise fall back to offset anchoring.
        if (pos.sectionKey !== undefined && this.scrollToRegion(container, pos)) return;

        this.scrollToOffset(container, pos);
    }

    /**
     * By-key scroll: places the sync point as {@link resolveSegmentTarget} says, or returns false
     * when it finds no shared key around the sender's position, so the caller falls back to offset
     * anchoring. Unlike the offset path, this one does not blend toward the sender's scroll
     * progress: {@link regionSpans} already makes both sides reach their ends together.
     */
    private scrollToRegion(container: Element, pos: ViewportScrollTarget): boolean {
        const regions = this.syncRegions();
        if (!regions || pos.sectionKey === undefined) return false;

        // Region top/bottom as scroll positions, via the SAME helper the producer uses so both sides
        // agree on the boundary exactly.
        const spans = this.regionSpans(container, regions);
        const target = resolveSegmentTarget(
            regions.map((r, i) => ({ key: r.key, parentKey: r.parentKey, top: spans[i].top })),
            this.syncPointEnd(container),
            pos.sectionKey,
            pos.sections,
            pos.scrollProgress,
            this.segmentBand,
            pos.viewportFraction,
            container.clientHeight
        );
        if (target === undefined) return false;

        // Dedup identical re-applies (per-frame)
        const frac = Math.min(1, Math.max(0, pos.sectionFraction ?? 0));
        const last = this.lastAppliedViewportScrollPosition;
        if (
            last &&
            last.sectionKey === pos.sectionKey &&
            last.sectionFraction !== undefined &&
            Math.abs(last.sectionFraction - frac) < 0.001 &&
            sameScrollProgress(last.scrollProgress, pos.scrollProgress)
        ) {
            return true;
        }
        this.lastAppliedViewportScrollPosition = {
            begin: pos.begin,
            fraction: Math.min(1, Math.max(0, pos.fraction)),
            scrollProgress: pos.scrollProgress,
            sectionKey: pos.sectionKey,
            sectionFraction: frac,
        };

        // target is where the sync point goes, not the viewport top
        this.applyScrollTop(container, this.scrollTopForSyncPoint(container, target));
        return true;
    }

    /**
     * Whether the viewport is where a sync put it, or is still gliding there. Forgets the applied
     * position once the viewport is found anywhere else, so a later scroll that happens to end
     * there again counts as the user's.
     */
    isApplyingViewportSync(): boolean {
        const container = this.getScrollContainer();
        if (container && this.appliedScrollTop === container.scrollTop) return true;
        this.appliedScrollTop = undefined;
        return false;
    }

    /** Move the viewport to {@code scrollTop} for a sync, at once or gliding (see {@link glide}). */
    private applyScrollTop(container: Element, scrollTop: number): void {
        const maxScroll = Math.max(0, container.scrollHeight - container.clientHeight);
        const target = Math.min(maxScroll, Math.max(0, scrollTop));
        const gliding = this.glideFrame !== undefined;

        if (
            !this.glide ||
            (!gliding &&
                Math.abs(target - container.scrollTop) <
                    this.glideThreshold * container.clientHeight)
        ) {
            this.stopGlide();
            container.scrollTop = target;
            this.appliedScrollTop = container.scrollTop;
            return;
        }

        this.glideTarget = target;
        if (!gliding) {
            this.appliedScrollTop = container.scrollTop;
            this.glideLastTime = undefined;
            this.glideFrame = requestAnimationFrame((t) => this.glideStep(container, t));
        }
    }

    private glideStep(container: Element, time: number): void {
        this.glideFrame = undefined;
        const target = this.glideTarget;
        if (target === undefined) return;

        // Something else moved the viewport, most likely the user: they take over
        if (this.appliedScrollTop === undefined || container.scrollTop !== this.appliedScrollTop) {
            this.stopGlide();
            return;
        }

        const dt = this.glideLastTime === undefined ? 16 : time - this.glideLastTime;
        this.glideLastTime = time;

        const current = container.scrollTop;
        const remaining = target - current;
        // At least a pixel per frame, so rounding of scrollTop cannot stall the glide
        const step = remaining * (1 - Math.exp(-dt / this.glideTimeConstantMs));
        const next =
            Math.abs(remaining) <= 1
                ? target
                : current + Math.sign(remaining) * Math.max(1, Math.abs(step));
        container.scrollTop = next;
        this.appliedScrollTop = container.scrollTop;

        // Arrived, or could not move any further (e.g. the content shrank under the target)
        if (Math.abs(target - container.scrollTop) <= 1 || container.scrollTop === current) {
            this.glideTarget = undefined;
            return;
        }
        this.glideFrame = requestAnimationFrame((t) => this.glideStep(container, t));
    }

    private stopGlide(): void {
        if (this.glideFrame !== undefined) cancelAnimationFrame(this.glideFrame);
        this.glideFrame = undefined;
        this.glideTarget = undefined;
    }

    /**
     * Read a region start element's viewport-top. This runs on every scroll frame and must never
     * write to the DOM: un-secluding sections merely being measured would defeat the
     * seclusion/windowing optimization for large documents. A top-level secluded section is
     * {@code visibility: hidden} and keeps its box; elements inside a secluded section are not
     * measured here (see {@link regionTopScrollPos}).
     */
    private elementTop(element: Element): number {
        return element.getBoundingClientRect().top;
    }

    /** Offset-anchored scroll, used when by-key sync does not apply. */
    private scrollToOffset(container: Element, pos: ViewportScrollTarget): void {
        const range = offsetToRange(this.root, pos.begin, pos.begin);
        if (!range) return;

        const start = range.startContainer;
        const startElement = start instanceof Element ? start : start.parentElement;
        const block = this.closestAnchorElement(startElement);
        if (!block) return;

        // Skip if the scroll request would not actually move the scroll position
        const fraction = Math.min(1, Math.max(0, pos.fraction));
        const last = this.lastAppliedViewportScrollPosition;
        if (
            last &&
            last.sectionKey === undefined && // only dedup against a prior offset-path apply (see field doc)
            last.begin === pos.begin &&
            Math.abs(last.fraction - fraction) < 0.01 &&
            sameScrollProgress(last.scrollProgress, pos.scrollProgress)
        ) {
            return;
        }
        this.lastAppliedViewportScrollPosition = {
            begin: pos.begin,
            fraction,
            scrollProgress: pos.scrollProgress,
        };

        // If necessary unhide the target block before measuring. We unhide only if hidden because a write (even a noop one) to the DOM can invalidate the layout
        if (block.closest('.iaa-secluded')) {
            removeClassFromAncestors(block, 'iaa-secluded', this.root);
        }

        const containerRect = container.getBoundingClientRect();
        const blockRect = block.getBoundingClientRect();
        // The anchor point in the scrolled content, blended toward the sender's progress near the
        // ends, then the scrollTop that puts the sync point there
        const anchorPoint =
            container.scrollTop + (blockRect.top - containerRect.top) + fraction * blockRect.height;
        const syncPoint = blendTowardScrollProgress(
            anchorPoint,
            pos.scrollProgress,
            this.syncPointEnd(container)
        );
        this.applyScrollTop(container, this.scrollTopForSyncPoint(container, syncPoint));
    }

    /**
     * The current document's flat scroll-sync regions, memoized and rebuilt when the tocRoot changes.
     * Returns undefined when there is no structure/outline (then sync uses plain offset anchoring).
     */
    private syncRegions(): SyncRegion[] | undefined {
        const tocRoot = this.structure?.tocRoot();
        if (!tocRoot) return undefined;

        if (this.regions === undefined || this.regionsTocRoot !== tocRoot) {
            const docEnd = calculateEndOffset(this.root, this.root);
            this.regions = buildSyncRegions(this.root, tocRoot, docEnd);
            this.regionsTocRoot = tocRoot;
        }
        return this.regions.length ? this.regions : undefined;
    }

    /**
     * Reset the per-frame dedup memory. Call when a (programmatic) scroll completes so the next sync
     * position is applied even if it coincides with the last one applied during the scroll.
     */
    reset(): void {
        this.lastAppliedViewportScrollPosition = undefined;
    }

    /**
     * Try locating the block-level element that straddles {@code seamY}, the sync point in client
     * coordinates.
     */
    private findAnchorAtSeam(containerRect: DOMRect, seamY: number): Element | null {
        const doc = this.root.ownerDocument;
        const rootRect = this.root.getBoundingClientRect();
        const left = Math.max(containerRect.left, rootRect.left);
        const right = Math.min(containerRect.right, rootRect.right);
        if (right <= left) return null;

        const width = right - left;
        // x offsets guard against a probe column falling in a gutter/float; y offsets step past
        // margin gaps. Any in-root node lets blockAtSeam() resolve the anchor by structure. Probe on
        // both sides of the seam, below first, and only inside the viewport: at the bottom of the
        // document the seam is the viewport's bottom edge.
        const entryX = [left + width * 0.5, left + width * 0.25, left + width * 0.75];
        const entryY = [8, 24, 48, 96]
            .flatMap((d) => [seamY + d, seamY - d])
            .filter((y) => y >= containerRect.top && y < containerRect.bottom);

        for (const y of entryY) {
            for (const x of entryX) {
                for (const el of doc.elementsFromPoint(x, y)) {
                    if (el === this.root || !this.root.contains(el)) continue;
                    // Skip our own overlays - they sit above the content but carry no offsets
                    if (el.closest('svg')) continue;
                    if (el.closest(OVERLAY_SELECTOR)) continue;

                    const anchor = this.blockAtSeam(el, seamY);
                    if (anchor) return anchor;
                }
            }
        }

        return null;
    }

    /**
     * From an entry node near the seam, return the block-level element with text that straddles
     * {@code seamY}, searching in document order. If the entry node's block sits entirely above the
     * seam, advance to the following blocks; if entirely below, accept it as the first block after
     * the seam. Straddling is preferred because the fraction in {@link getViewportScrollPosition} is
     * only meaningful for a block the seam actually crosses.
     * <p>
     * When straddling blocks nest, return the <em>innermost</em> one: it gives the finest-grained
     * height for the scroll fraction.
     */
    private blockAtSeam(entry: Element, seamY: number): Element | null {
        let start = this.closestBlockWithText(entry);
        if (!start) return null;

        const straddles = (el: Element) => {
            const rect = el.getBoundingClientRect();
            return rect.top <= seamY && rect.bottom > seamY;
        };

        // The entry probe can land on a block just below the seam (e.g. the table row under the one
        // that crosses it). Back up while the start block sits entirely below the seam and the
        // previous block reaches up to or across it, since the forward walk never revisits it.
        while (start.getBoundingClientRect().top > seamY) {
            const prev = this.prevBlockWithText(start);
            if (!prev || prev.getBoundingClientRect().top > seamY) break;
            start = prev;
        }

        let firstBelow: Element | null = null;
        let innermostStraddler: Element | null = null;
        let current: Element | null = start;
        while (current && current !== this.root) {
            if (straddles(current)) {
                // Descend into nested straddlers, but stop once the walk leaves this subtree - a
                // later straddling sibling is a different, lower block, not a finer anchor.
                if (!innermostStraddler || innermostStraddler.contains(current)) {
                    innermostStraddler = current;
                } else {
                    break;
                }
            } else if (innermostStraddler) {
                // Blocks above the seam inside the straddler (its heading, say) are followed by the
                // paragraph that does straddle it, so keep walking while inside its subtree. Leaving
                // the subtree ends the straddling run; keep the innermost we found.
                if (!innermostStraddler.contains(current)) break;
            } else if (!firstBelow) {
                const rect = current.getBoundingClientRect();
                if (rect.top > seamY) {
                    firstBelow = current; // first fully-below block, a fallback if nothing straddles
                }
            }
            current = this.nextBlockWithText(current);
        }

        return innermostStraddler ?? firstBelow ?? start;
    }

    private closestBlockWithText(element: Element | null): Element | null {
        let current = element;
        while (current && current !== this.root) {
            if (this.isBlockWithText(current)) return current;
            current = current.parentElement;
        }
        return null;
    }

    private nextBlockWithText(element: Element): Element | null {
        const walker = this.blockWalker();
        walker.currentNode = element;
        return walker.nextNode() as Element | null;
    }

    private prevBlockWithText(element: Element): Element | null {
        const walker = this.blockWalker();
        walker.currentNode = element;
        return walker.previousNode() as Element | null;
    }

    private blockWalker(): TreeWalker {
        return this.root.ownerDocument.createTreeWalker(this.root, NodeFilter.SHOW_ELEMENT, {
            acceptNode: (node) =>
                this.isBlockWithText(node as Element)
                    ? NodeFilter.FILTER_ACCEPT
                    : NodeFilter.FILTER_SKIP,
        });
    }

    private isBlockWithText(element: Element): boolean {
        // The editor root is never a content anchor: it spans the whole viewport, so its height is a
        // meaningless fraction denominator
        if (element === this.root) return false;
        if (!element.textContent) return false;
        if (element.closest('svg')) return false;
        if (element.closest(OVERLAY_SELECTOR)) return false;
        return ANCHOR_DISPLAY_CATEGORIES.has(window.getComputedStyle(element).display);
    }

    private closestAnchorElement(element: Element | null): Element | null {
        let current = element;
        while (current && current !== this.root) {
            if (ANCHOR_DISPLAY_CATEGORIES.has(window.getComputedStyle(current).display)) {
                return current;
            }
            current = current.parentElement;
        }
        return current;
    }
}
