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
import type { Offsets } from '../model/Offsets';

/**
 * Vertical scroll position: where an editor's sync point currently falls in the document, expressed
 * layout-independently along the character-offset axis. The sync point is a horizontal line in the
 * viewport that the editor chooses: the viewport top, or a line that moves with the scroll position.
 * The receiver puts the same position at its own sync point. The position is the anchor element the
 * sync point cuts through, as the interval {@code begin..end}, plus {@code fraction}: how far the
 * sync point has progressed through it. Together they denote the fractional character offset
 * {@code begin + fraction*(end-begin)}.
 */
export type ViewportScrollPosition = {
    /** Document character offset at which the topmost visible anchor element begins. */
    begin: number;
    /** End offset of the anchor element, so a receiver can size the anchor. */
    end: number;
    /** Progress (0..1) of the sync point through the anchor element. */
    fraction: number;
    /**
     * The source's overall progress: how far its sync point has got through the range it moves in,
     * in [0,1] (0 = scrolled to the very top, 1 = scrolled to the very bottom). For a sync point at
     * the viewport top that is scrollTop / maxScroll.
     */
    scrollProgress?: number;
    /**
     * Key of the scroll-sync region covering the sync point, when the two documents share a
     * structure (regions are derived from DocumentStructureStrategy section keys). Lets a receiver
     * align by matching region rather than by absolute character offset -- correct even between two
     * different documents. Absent when there is no structure or no covering region; the receiver then
     * falls back to offset/fraction/scrollProgress anchoring.
     */
    sectionKey?: string;
    /**
     * The sync point's position within the keyed region as a pixel fraction in [0,1] (paired with
     * sectionKey). The receiver places its own sync point at the same fraction of its matching
     * region's pixel span.
     */
    sectionFraction?: number;
    /**
     * All of the source's scroll-sync regions in document order, with where each starts. Lets a
     * receiver without a region for {@code sectionKey} interpolate between the nearest regions
     * before and after it that it does have, falling back to offset sync if it has neither. Present
     * whenever {@code sectionKey} is, together with {@code scrollProgress}.
     */
    sections?: SectionTop[];
    /**
     * The source's viewport height in the unit of {@code scrollProgress}, so a receiver can measure
     * lengths in the source's viewport heights. Present together with {@code sections}.
     */
    viewportFraction?: number;
};

/**
 * Where one of the source's scroll-sync regions starts, see {@link ViewportScrollPosition.sections}.
 */
export type SectionTop = {
    /** The region's key. */
    key: string;
    /**
     * Where the region starts, in the same unit as {@code scrollProgress}: the position of the sync
     * point at which it reaches the region's start, as a fraction of the range the sync point moves
     * in, in [0,1]. A region starting beyond the end of that range has 1.
     */
    top: number;
    /**
     * The key of the nearest enclosing region, or undefined when no region encloses it. Lets a
     * receiver tell subsections it lacks from sections it lacks.
     */
    parent?: string;
};

/**
 * Input accepted by {@link AnnotationEditor.scrollToViewportPosition}. A relaxation of
 * {@link ViewportScrollPosition} in which {@code end} is optional: the receiver only needs
 * {@code begin} + {@code fraction} to align its viewport and defaults {@code end} to {@code begin}
 * when absent. A {@link ViewportScrollPosition} is assignable here, so producers can forward one
 * directly.
 */
export type ViewportScrollTarget = {
    /** Document character offset at which the anchor element begins. */
    begin: number;
    /** End offset of the anchor element. Defaults to {@code begin} when omitted. */
    end?: number;
    /** Progress (0..1) of the sync point through the anchor element. */
    fraction: number;
    /** See {@link ViewportScrollPosition.scrollProgress}. */
    scrollProgress?: number;
    /**
     * Key of the scroll-sync region to align to. When this receiver has a matching region it scrolls
     * region-fractionally by {@code sectionFraction} instead of using {@code begin}; on a miss it is
     * ignored.
     */
    sectionKey?: string;
    /** See {@link ViewportScrollPosition.sectionFraction}. */
    sectionFraction?: number;
    /** See {@link ViewportScrollPosition.sections}. */
    sections?: SectionTop[];
    /** See {@link ViewportScrollPosition.viewportFraction}. */
    viewportFraction?: number;
};

/**
 * The peer-facing view of the host-page scroll-sync hub: the subset an editor uses to add and
 * remove itself as a sync peer. Kept minimal (and defined here rather than importing the concrete
 * hub) so an editor can register without depending on the external-editor module that owns the hub.
 */
export interface ViewportSyncPeer {
    /**
     * Register {@code aEditor} as a scroll-sync peer under {@code aId}. The editor passes the element
     * whose scrolling moves its viewport as {@code aScope} - the hub listens on that element's owner
     * document and uses it to tell the editor's own scroll apart from unrelated scrolls of nested
     * scrollers sharing the same document. Omit {@code aScope} when the editor scrolls its whole
     * document.
     */
    register(aId: string, aEditor: AnnotationEditor, aScope?: Element): void;

    /** Remove the peer previously registered under {@code aId}, detaching its scroll listener. */
    unregister(aId: string): void;
}

export interface AnnotationEditor {
    loadAnnotations(): void;

    /**
     * Editor should scroll to the given offset. The offset is given in relation to the entire
     * doocument, not to the viewport. So if the editor is showing only a part of the document in
     * its viewport, the offset should be adjusted accordingly.
     */
    scrollTo(args: { offset: number; position?: string; pingRanges?: Offsets[] }): void;

    /**
     * Report the vertical scroll position currently at the editor's sync point (see
     * {@link ViewportScrollPosition}). May return null when the position cannot be determined (e.g.
     * before initialization has completed).
     */
    getViewportScrollPosition?(): ViewportScrollPosition | null;

    /**
     * Place the editor's sync point at the given anchor offset plus intra-anchor fraction. The
     * editor resolves the offset in its own layout and clamps to its own scroll bounds.
     * <p>
     * The anchor + fraction denote a fractional character offset (begin + fraction*(end-begin)) at
     * the sync point. Interpolating in character space rather than pixels keeps the sync stable
     * when the two editors wrap the same text to different heights - each side converts that offset
     * to a pixel position with its own layout. {@code end} defaults to {@code begin} if omitted.
     */
    scrollToViewportPosition?(pos: ViewportScrollTarget): void;

    /**
     * Whether the viewport is where {@link scrollToViewportPosition} put it, or is still moving there,
     * so that the scroll events it causes are not mirrored back. False once anything else has moved
     * it. Editors without it are judged by their scroll position after the call.
     */
    isApplyingViewportSync?(): boolean;

    /**
     * Add this editor to the scroll-sync {@code aHub} under {@code aId} (a host-page markup id chosen
     * by the backend, which drives linking). The editor registers itself - rather than the hub or
     * the factory reaching in - because it owns its layout and so knows which of the elements it owns
     * actually scrolls; it passes that element to {@link ViewportSyncPeer.register} as the scope.
     * The editor should call this once its viewport is built (so the scroll container exists), and
     * re-run it if the container is rebuilt. Implementations remember {@code aHub}/{@code aId} so
     * {@link disconnectViewportSync} can undo it.
     */
    connectViewportSync?(aHub: ViewportSyncPeer, aId: string): void;

    /** Remove this editor from the hub it was connected to via {@link connectViewportSync}. */
    disconnectViewportSync?(): void;

    destroy(): void;
}
