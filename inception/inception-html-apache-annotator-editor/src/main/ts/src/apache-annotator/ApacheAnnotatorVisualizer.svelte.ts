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
import './ApacheAnnotatorEditor.scss';
import {
    ViewportTracker,
    unpackCompactAnnotatedTextV2,
    type AnnotationEditor,
    type DiamAjax,
    type DiamLoadAnnotationsOptions,
    type VID,
    type ViewportScrollPosition,
    type ViewportScrollTarget,
    type ViewportSyncPeer,
    offsetToRange,
    AnnotatedText,
    Span,
    TextMarker,
    type Offsets,
    AnnotationOverEvent,
    AnnotationOutEvent,
} from '@inception-project/inception-js-api';
import { type CompactAnnotatedText } from '@inception-project/inception-js-api/src/model/compact_v2';
import { annotatorState } from './ApacheAnnotatorState.svelte';
import { ResizeManager } from './ResizeManager';
import { bgToFgColor } from '@inception-project/inception-js-api/src/util/Coloring';
import { SectionAnnotationVisualizer } from './SectionAnnotationVisualizer';
import { SectionAnnotationCreator } from './SectionAnnotationCreator';
import { RelationVisualizer } from './RelationVisualizer';
import { RelationGripLayer, GRIP_CLASS } from './RelationGripLayer';
import { RelationDragController, type PickRelationTarget } from './RelationDragController';
import {
    ApacheAnnotatorSyncController,
    type SyncStructureAccessors,
} from './ApacheAnnotatorSyncController';
import { endMarginLostWhenEmptied } from './SectionMaterialization';
import {
    groupHighlightsByVid,
    removeClassFromAncestors,
    safeHighlightText,
    compileNsSelector,
} from './Utilities';

export const NO_LABEL = '◌';
export const ERROR_LABEL = '🔴';
export const INFO_LABEL = 'ℹ️';
export const WARN_LABEL = '⚠️';

const SCROLL_ITERATION_MS = 100;
// After scroll position stabilises we wait this long for any late DOM mutations
// (e.g. async section-summary spacers from a render that runs after the scroll
// has reached its target) before declaring the scroll complete. The MutationObserver
// installed during scrolling cancels the deferred completion if a mutation arrives.
const SCROLL_QUIET_PERIOD_MS = 400;
const RESIZE_DEBOUNCE_MS = 500;
const LOAD_ANNOTATIONS_DEBOUNCE_MS = 150;
const PING_MARKER_REMOVAL_DELAY_MS = 2000;
const SECLUSION_MARGIN_PX = 1000;
const HOVER_DEBOUNCE_MS = 250;

export class ApacheAnnotatorVisualizer {
    private ajax: DiamAjax;
    readonly root: Element;
    private toCleanUp = new Set<Function>();
    private resizer: ResizeManager;
    private tracker: ViewportTracker;
    private rootResizeTimeout: number | undefined = undefined;
    private rootResizeTracker: ResizeObserver | undefined = undefined;
    private rootDimensions: { width: number; height: number } | undefined = undefined;
    private optimizingLayout = false;
    private sectionTracker: IntersectionObserver | undefined = undefined;

    private sectionSelector: string;
    private sectionAnnotationVisualizer: SectionAnnotationVisualizer;
    private sectionAnnotationCreator: SectionAnnotationCreator;
    private relationVisualizer: RelationVisualizer;
    private relationGripLayer: RelationGripLayer;
    private relationDragController: RelationDragController;
    private syncController: ApacheAnnotatorSyncController;

    private data?: AnnotatedText;

    private scrolling = false;
    /** Scroll container the relation re-band listener is attached to; detached on destroy. */
    private scrollContainer?: Element;
    /** Pending rAF handle coalescing scroll events into one relation re-band per frame. */
    private relationScrollRaf?: number;
    private lastScrollTop: number | undefined = undefined;
    private lastMarkerTop: number | undefined = undefined;
    private scrollMutationObserver: MutationObserver | undefined = undefined;
    private removeScrollMarkers: (() => void)[] = [];
    private removeScrollMarkersTimeout: number | undefined = undefined;
    private removePingMarkers: (() => void)[] = [];
    private removePingMarkersTimeout: number | undefined = undefined;
    private loadAnnotationsTimeout: number | undefined = undefined;
    private addHoverTimeout: number | undefined = undefined;
    private removeHoverTimeout: number | undefined = undefined;

    private alpha = '55';

    private _protectedElementSelector?: string = undefined;
    private protectedElementsMatcher?: (el: Element) => boolean = undefined;

    public get protectedElementSelector(): string | undefined {
        return this._protectedElementSelector;
    }

    public set protectedElementSelector(v: string | undefined) {
        this._protectedElementSelector = v;
        if (v == null || v.trim() === '') {
            this.protectedElementsMatcher = undefined;
        } else {
            this.protectedElementsMatcher = compileNsSelector(v) || undefined;
        }
    }

    constructor(
        element: Element,
        ajax: DiamAjax,
        sectionSelector: string,
        pickRelationTarget: PickRelationTarget
    ) {
        this.ajax = ajax;
        this.root = element;
        this.sectionSelector = sectionSelector;

        this.optimizeLayout('constructor');

        this.tracker = new ViewportTracker(
            this.root,
            () => {
                // We can optimize scrolling by deferring rendering during the scroll
                this.doLoadAnnotations({ allowRenderSkip: true });
            },
            {
                ignoreSelector:
                    '.iaa-section-control, .iaa-vertical-marker-focus, .iaa-ping-marker, iaa-visible-annotations-panel, iaa-visible-annotations-panel-spacer',
            }
        );

        this.resizer = new ResizeManager(this, this.ajax);

        this.sectionAnnotationCreator = new SectionAnnotationCreator(
            this.root,
            this.ajax,
            this.sectionSelector
        );
        this.sectionAnnotationVisualizer = new SectionAnnotationVisualizer(
            this.root,
            this.ajax,
            this.sectionSelector
        );
        this.relationVisualizer = new RelationVisualizer(this.root, this.ajax, (vid) =>
            this.getHighlightsForAnnotation(vid)
        );
        this.relationDragController = new RelationDragController(
            this.root,
            this.ajax,
            this.relationVisualizer,
            pickRelationTarget
        );
        this.relationGripLayer = new RelationGripLayer(
            this.root,
            (vid) => this.getHighlightsForAnnotation(vid),
            (grip, e) => this.relationDragController.onGripPointerDown(grip, e)
        );

        // Event handlers for the resizer component
        this.root.addEventListener('mouseover', (e) => this.showResizer(e));

        // Event handlers for custom events. A relation-creation grip proxies its span, so hovering a
        // grip emits the same enter/leave events as hovering the span text — bringing up the detail
        // popover for the grip's annotation (the events bubble through the overlay to the editor root).
        this.root.addEventListener('mouseover', (event) => {
            if (!(event instanceof MouseEvent)) return;
            const vid = this.hoveredAnnotationVid(event.target);
            if (!vid) return;
            const annotation = this.data?.getAnnotation(vid);
            if (!annotation) return;
            (event.target as Element).dispatchEvent(new AnnotationOverEvent(annotation, event));
        });
        this.root.addEventListener('mouseout', (event) => {
            if (!(event instanceof MouseEvent)) return;
            const vid = this.hoveredAnnotationVid(event.target);
            if (!vid) return;
            const annotation = this.data?.getAnnotation(vid);
            if (!annotation) return;
            (event.target as Element).dispatchEvent(new AnnotationOutEvent(annotation, event));
        });

        // Add event handlers for highlighting extent of the annotation the mouse is currently over
        this.root.addEventListener('mouseover', (e) => this.addHoverHighlight(e as MouseEvent));
        this.root.addEventListener('mouseout', (e) => this.removeHoverHighlight(e as MouseEvent));

        // Surface relation-creation grips for the hovered span stack.
        this.root.addEventListener('mouseover', (e) =>
            this.relationGripLayer.handlePointerOver((e as MouseEvent).target)
        );
        this.root.addEventListener('mouseleave', () => this.relationGripLayer.clear());

        // Re-band relations on manual scroll, independent of the ViewportTracker (see onContentScroll).
        this.scrollContainer = this.root.closest('.i7n-wrapper') || this.root;
        this.scrollContainer.addEventListener('scroll', this.onContentScroll, { passive: true });

        // Editor-to-editor viewport synchronization. The scroll container is read lazily so the
        // controller always sees the current one.
        this.syncController = new ApacheAnnotatorSyncController(
            this.root,
            () => this.scrollContainer
        );
    }

    /**
     * Provide the document-structure inputs used for by-key scroll sync. Forwarded to the sync
     * controller; called by the editor once the document outline has been built.
     */
    public setSyncDocumentStructure(structure: SyncStructureAccessors): void {
        this.syncController.setDocumentStructure(structure);
    }

    /**
     * Apply the current relation-label visibility preference without re-rendering. The labels are
     * already in the overlay; this just flips the CSS mode class (see RelationVisualizer).
     */
    public refreshRelationLabelMode(): void {
        this.relationVisualizer.setLabelsAlwaysVisible(annotatorState.showRelationLabels);
    }

    public optimizeLayout(trigger: string) {
        if (this.optimizingLayout) return;

        try {
            console.log('optimizeLayout called by ' + trigger);
            this.optimizingLayout = true;

            if (this.sectionTracker) {
                this.sectionTracker.disconnect();
            }

            if (this.rootResizeTracker) {
                this.rootResizeTracker.disconnect();
            }

            this.root
                .querySelectorAll('.iaa-secluded')
                .forEach((e) => e.classList.remove('iaa-secluded'));

            this.materializeWidthAndHeight(this.root, this.sectionSelector);

            // Nothing is secluded until the tracker below reports, so the scroll sync can record
            // where everything is now. Absent on the first call, from the constructor; the sync
            // controller records once it gets the document structure instead.
            this.syncController?.recordLayout();

            this.sectionTracker = this.sectionSeclusionTracker(this.root, this.sectionSelector);
            this.rootResizeTracker = new ResizeObserver((entries) => {
                if (this.optimizingLayout || entries.length === 0) return;

                let dim = entries[0].contentRect; // Only observing one element
                // Only re-optimize on width changes. Height fluctuates as annotations
                // render into sections (materializeWidthAndHeight intentionally locks
                // width but only sets minHeight, allowing height to grow), so reacting
                // to height changes here would feed back into another optimizeLayout.
                if (!this.rootDimensions || dim.width !== this.rootDimensions.width) {
                    console.log(
                        `Root resized from ${this.rootDimensions?.width}/${this.rootDimensions?.height} to ${dim.width}/${dim.height}`
                    );
                    this.rootDimensions = { width: dim.width, height: dim.height };

                    if (this.rootResizeTimeout) {
                        this.cancelScheduled(this.rootResizeTimeout);
                        this.rootResizeTimeout = undefined;
                    }

                    this.rootResizeTimeout = this.schedule(RESIZE_DEBOUNCE_MS, () =>
                        this.optimizeLayout('resize observer')
                    );
                }
            });

            this.rootResizeTracker.observe(this.root);
        } finally {
            console.log('Finished optimizeLayout called by ' + trigger);
            this.optimizingLayout = false;
        }
    }

    private sectionSeclusionTracker(
        element: Element,
        sectionSelector: string
    ): IntersectionObserver | undefined {
        if (!sectionSelector) return;

        const observer = new IntersectionObserver(
            (entries) => {
                console.log('hideSectionsOutsideViewport: processing entries', entries.length);
                for (const entry of entries) {
                    const target = entry.target as HTMLElement;
                    if (!target) continue;

                    if (entry.isIntersecting) {
                        target.classList.remove('iaa-secluded');
                    } else {
                        target.classList.add('iaa-secluded');
                    }
                }
            },
            {
                root: element instanceof Element ? (element as Element) : null,
                rootMargin: `${SECLUSION_MARGIN_PX}px`,
                threshold: 0,
            }
        );

        const nodes = element.querySelectorAll(sectionSelector);
        console.log('hideSectionsOutsideViewport: found nodes count', nodes.length);
        nodes.forEach((n) => {
            if (!(n instanceof Element)) return;
            observer.observe(n);
        });

        return observer;
    }

    private materializeWidthAndHeight(element: Element, sectionSelector: string) {
        if (!sectionSelector) return;

        try {
            const nodes = element.querySelectorAll(sectionSelector);
            const measurements: { n: HTMLElement; height: number; width: number }[] = [];

            // Clear any previously set minHeight/minWidth styles
            nodes.forEach((n) => {
                if (!(n instanceof HTMLElement)) return;
                n.style.minHeight = '';
                n.style.minWidth = '';
                n.style.height = '';
                n.style.width = '';
                n.style.maxHeight = '';
                n.style.maxWidth = '';
                n.style.boxSizing = '';
            });

            // Read: batch all measurements. The browser calculates layout once here, and
            // reuses it for subsequent reads as long as no write operations occur in between.
            nodes.forEach((n) => {
                if (!(n instanceof HTMLElement)) return;

                const rect = n.getBoundingClientRect();
                let height = rect.height;
                let width = rect.width;

                // Fallbacks
                if (!height || height === 0) height = n.offsetHeight || n.scrollHeight || 0;
                if (!width || width === 0) width = n.offsetWidth || n.scrollWidth || 0;

                // Keep the margin that collapses out of the section, which the height leaves out,
                // when its nested sections are secluded (see endMarginLostWhenEmptied)
                if (height > 0) height += endMarginLostWhenEmptied(n);

                // Store the node and its new dimensions
                if (height > 0 || width > 0) {
                    measurements.push({ n, height, width });
                }
            });

            // Write: batch all mutations. Applying styles invalidates layout, but since we
            // are done reading, we don't force a recalculation until the next frame/paint.
            measurements.forEach(({ n, height, width }) => {
                if (height > 0 || width > 0) {
                    n.style.boxSizing = 'border-box';
                }
                if (height > 0) {
                    n.style.minHeight = `${height}px`;
                    // We just set the minHeight to allow growing when annotations are rendered
                    // n.style.height = `${height}px`
                    // n.style.maxHeight = `${height}px`
                }
                if (width > 0) {
                    n.style.minWidth = `${width}px`;
                    n.style.width = `${width}px`;
                    n.style.maxWidth = `${width}px`;
                }
            });
        } catch (e) {
            // eslint-disable-next-line no-console
            console.warn('defineMinExtensions failed for selector', sectionSelector, e);
        }
    }

    private showResizer(event: Event): void {
        if (!(event instanceof MouseEvent) || !(event.target instanceof HTMLElement)) return;

        // If a mouse button is pressed, we do not show the resizer so we do not interrupt drag operations
        if (event.buttons !== 0) return;

        const vid = event.target.getAttribute('data-iaa-id');
        if (vid) this.resizer.show(vid);
    }

    /**
     * The annotation vid a hover target represents: a highlight (`data-iaa-id`) or one of its
     * relation-creation grips, which proxy their span via `data-grip-vid`. The grip deliberately omits
     * `data-iaa-id` (so `highlights()` / the drop hit-test never treat it as an annotation), so it is
     * resolved separately here. This lets a grip hover drive the same span echo (`iaa-hover`) and
     * detail popover as hovering the span text.
     */
    private hoveredAnnotationVid(target: EventTarget | null): VID | null {
        if (!(target instanceof Element)) return null;
        return (
            target.getAttribute('data-iaa-id') ??
            target.closest<HTMLElement>('.' + GRIP_CLASS)?.dataset.gripVid ??
            null
        );
    }

    private addHoverHighlight(event: MouseEvent) {
        if (!(event.target instanceof Element)) return;

        const vid = this.hoveredAnnotationVid(event.target);
        if (!vid) return;

        if (this.addHoverTimeout) {
            this.cancelScheduled(this.addHoverTimeout);
            this.addHoverTimeout = undefined;
        }

        this.addHoverTimeout = this.schedule(HOVER_DEBOUNCE_MS, () => {
            if (this.removeHoverTimeout) {
                window.clearTimeout(this.removeHoverTimeout);
                this.removeHoverTimeout = undefined;
            }

            this.root
                .querySelectorAll('.iaa-hover')
                .forEach((e) => e.classList.remove('iaa-hover'));
            this.getHighlightsForAnnotation(vid).forEach((e) => e.classList.add('iaa-hover'));
        });
    }

    private removeHoverHighlight(event: MouseEvent) {
        if (!(event.target instanceof Element)) return;

        // Intentionally using window.setTimeout here because we want to wait a *minimum* amount of time
        // before removing the hover. this.schedule() will try to run as fast as possible
        if (this.removeHoverTimeout) {
            window.clearTimeout(this.removeHoverTimeout);
            this.removeHoverTimeout = undefined;
        }

        this.removeHoverTimeout = window.setTimeout(() => {
            this.root
                .querySelectorAll('.iaa-hover')
                .forEach((e) => e.classList.remove('iaa-hover'));
        }, HOVER_DEBOUNCE_MS);
    }

    loadAnnotations(): void {
        this.doLoadAnnotations({ allowRenderSkip: false });
    }

    /**
     * Re-render highlights/arcs from the cached annotation data without a server round-trip.
     * Use for view-only changes (e.g. line spacing) where the geometry changed but the
     * annotation data did not. No-ops until the first load has populated the cache.
     */
    rerender(): void {
        if (this.data) {
            this.renderAnnotations(this.data);
        }
    }

    private doLoadAnnotations(opts: { allowRenderSkip: boolean }): void {
        const allowSkip = opts.allowRenderSkip;
        if (this.loadAnnotationsTimeout) {
            this.cancelScheduled(this.loadAnnotationsTimeout);
            this.loadAnnotationsTimeout = undefined;
        }

        const loader = () => {
            // Defer loading while a scroll is in progress — re-rendering during
            // every scroll iteration would generate a lot of useless ajax/DOM work
            // for intermediate viewport ranges nobody will look at.
            //
            // The scrollTo loop releases the deferral (sets `scrolling = false`) at
            // the moment it believes the scroll has reached its target, then waits
            // through a quiet period during which the loader is allowed to run. If
            // the resulting render mutates the layout, the scroll MutationObserver
            // re-suspends scrolling and starts another iteration; otherwise the
            // quiet period elapses and the scroll completes.
            if (this.scrolling) {
                this.loadAnnotationsTimeout = this.schedule(LOAD_ANNOTATIONS_DEBOUNCE_MS, loader);
                return;
            }

            const options: DiamLoadAnnotationsOptions = {
                range: this.tracker.currentRange,
                includeText: false,
                clipSpans: false,
                // Request un-clipped arcs with real per-endpoint placeholders (real VID and
                // window-relative offset) instead of the shared VID_BEFORE/VID_AFTER sentinels,
                // and pull in relations whose endpoints lie outside the window. Stub tips,
                // far-endpoint navigation and labels all rely on these real endpoints.
                clipArcs: false,
                longArcs: true,
                format: 'compact_v2',
            };

            console.log(`Loading annotations for range ${JSON.stringify(options.range)}`);
            const startTime = performance.now();

            this.ajax.loadAnnotations(options).then((doc: CompactAnnotatedText) => {
                this.loadAnnotationsTimeout = undefined;

                this.data = unpackCompactAnnotatedTextV2(doc);
                const endTime = performance.now();
                console.log(`Loading annotations took ${endTime - startTime}ms`);

                this.renderAnnotations(this.data, { allowSkip });
            });
        };

        this.loadAnnotationsTimeout = this.schedule(LOAD_ANNOTATIONS_DEBOUNCE_MS, loader);
    }

    private renderAnnotations(doc: AnnotatedText, opts?: { allowSkip?: boolean }): void {
        // Pause tracker while performing DOM writes to avoid visibility feedback loops.
        this.tracker?.pause();

        try {
            console.log(`Client-side rendering started`);
            const startTime = performance.now();

            this.clearHighlights();
            this.resizer.hide();
            // Grips reference the old highlight DOM; drop them before it is torn down and rebuilt.
            this.relationGripLayer.clear();

            if (doc.spans) {
                console.log(`Loaded ${doc.spans.size} span annotations`);
                doc.spans.forEach((span) => this.renderSpanAnnotation(doc, span));

                this.removeSpuriousZeroWidthHighlights();
                if (!annotatorState.showEmptyHighlights) {
                    this.removeWhitepaceOnlyHighlights();
                }

                this.postProcessHighlights();
            }

            if (annotatorState.showAggregatedLabels) {
                this.sectionAnnotationVisualizer.render(doc, opts);
            } else {
                this.sectionAnnotationVisualizer.clear();
            }

            this.sectionAnnotationCreator.render(doc);

            if (doc.textMarkers) {
                doc.textMarkers.forEach((marker) => this.renderTextMarker(doc, marker));
                this.getAllTextMarkers().forEach((e) => {
                    if (!e.textContent) {
                        e.remove();
                    }
                });
                this.renderVerticalSelectionMarker(doc);
            }

            this.refreshRelationLabelMode();
            this.renderRelations(doc);

            const endTime = performance.now();
            console.log(`Client-side rendering took ${endTime - startTime}ms`);
        } finally {
            // Resume the tracker after a couple of frames so layout can stabilise.
            // Suppress the initial refresh because the rendering pass already
            // produced up-to-date annotations and an immediate reload can
            // trigger a feedback loop.
            this.tracker?.resume({ waitFrames: 2, suppressInitialRefresh: true });
        }
    }

    /**
     * Draw the relation overlay for `doc`, banded around the *current* viewport. Split out of
     * renderAnnotations so it can also run on bare scroll (see onContentScroll) without a full
     * span re-render or an Ajax reload.
     */
    private renderRelations(doc: AnnotatedText, remeasureContent = true): void {
        if (doc.relations) {
            const container = this.root.closest('.i7n-wrapper') || this.root;
            this.relationVisualizer.render(
                doc,
                {
                    viewport: container.getBoundingClientRect(),
                    seclusionMargin: SECLUSION_MARGIN_PX,
                },
                { remeasureContent }
            );
        } else {
            this.relationVisualizer.clear();
        }
    }

    /**
     * Re-band the relation overlay on manual scroll. Arcs are only drawn for a band around the
     * current viewport (perf); the band is expressed in viewport coordinates and therefore moves
     * with the viewport on *every* scroll frame. This is a paint concern, distinct from the
     * ViewportTracker's data window: the tracker decides which offset range to fetch/render (coarse,
     * debounced, and deliberately a superset that only changes when new content scrolls in — see
     * ViewportTracker.handleIntersectRange), whereas the band must follow the viewport continuously
     * even while the data window stays put. So this handler is not a workaround for the tracker; it
     * would be needed even with a perfectly tight tracker. Recompute the band directly from
     * already-loaded data on each scroll frame; this is cheap (redraws arcs only — no Ajax, no span
     * re-render). Skipped during programmatic scrolling, which drives its own render at completion.
     */
    private onContentScroll = (): void => {
        if (this.scrolling) return;
        if (this.relationScrollRaf !== undefined) return;
        this.relationScrollRaf = requestAnimationFrame(() => {
            this.relationScrollRaf = undefined;
            // Bare scroll: the content has not reflowed, only the viewport moved. Skip the overlay
            // resize + bow-scale remeasure (see RelationVisualizer.render); just re-band and redraw.
            if (this.data) this.renderRelations(this.data, false);
        });
    };

    private renderVerticalSelectionMarker(doc: AnnotatedText) {
        // We assume for the moment that only one annotation can be selected at a time
        const selectedAnnotationVids = doc.markedAnnotations.get('focus') || [];
        if (selectedAnnotationVids.length === 0) return;

        const highlights = Array.from(this.root.querySelectorAll('.iaa-marker-focus'));
        if (highlights.length === 0) return;

        let top: number | undefined;
        let bottom: number | undefined;
        highlights.forEach((hl) => {
            const r = hl.getBoundingClientRect();
            // Is the highlighted element actually visible or is it "display: none"
            if (
                (hl.classList.contains('iaa-zero-width') && r.height !== 0) ||
                (r.width !== 0 && r.height !== 0)
            ) {
                top = top === undefined ? r.top : Math.min(top, r.top);
                bottom = bottom === undefined ? r.bottom : Math.max(bottom, r.bottom);
            }
        });

        if (top === undefined || bottom === undefined) {
            console.warn('Cannot determine top/bottom for vertical selection marker');
            return;
        }

        const wrapper = this.root.closest('.i7n-wrapper');
        const scrollerContainerRect =
            wrapper?.getBoundingClientRect() || this.root.getBoundingClientRect();
        const scrollTop = wrapper?.scrollTop || this.root.scrollTop || 0;

        const vhl = this.root.ownerDocument.createElement('div');
        vhl.classList.add('iaa-vertical-marker-focus');
        vhl.style.top = `${top - scrollerContainerRect.top + scrollTop}px`;
        vhl.style.height = `${bottom - top}px`;
        this.root.appendChild(vhl);

        const selectedAnnotation = doc.spans.get(selectedAnnotationVids[0]);
        if (!selectedAnnotation) return;

        if (!selectedAnnotation.clippingFlags?.startsWith('s')) {
            const terminator = this.root.ownerDocument.createElement('div');
            terminator.classList.add('iaa-vertical-marker-focus');
            terminator.classList.add('terminator-start');
            terminator.addEventListener('click', (e) => {
                e.stopPropagation();
                this.scrollTo({
                    offset: selectedAnnotation.offsets[0][1] + doc.window[0],
                    position: 'unused',
                });
            });
            vhl.appendChild(terminator);
        }

        if (!selectedAnnotation.clippingFlags?.endsWith('e')) {
            const terminator = this.root.ownerDocument.createElement('div');
            terminator.classList.add('iaa-vertical-marker-focus');
            terminator.classList.add('terminator-end');
            terminator.addEventListener('click', (e) => {
                e.stopPropagation();
                this.scrollTo({
                    offset: selectedAnnotation.offsets[0][0] + doc.window[0],
                    position: 'unused',
                });
            });
            vhl.appendChild(terminator);
        }
    }

    /**
     * The highlighter may create highlighs that are empty (they do not even contain whitespace). This
     * method removes such highlights.
     */
    private removeSpuriousZeroWidthHighlights() {
        this.getAllHighlights().forEach((e) => {
            if (!e.classList.contains('iaa-zero-width') && !e.textContent) {
                // Removing the entire highlight element here should be fine as it should not contain any
                // relevant DOM nodes, e.g. nodes relevant to text offsets.
                e.remove();
            }
        });
    }

    /**
     * Some highlights may only contain whitepace. This method removes such highlights.
     */
    private removeWhitepaceOnlyHighlights(selector: string = '.iaa-highlighted') {
        const start = performance.now();
        const candidates = this.root.querySelectorAll(selector);
        console.log(
            `Found ${candidates.length} elements matching [${selector}] to remove whitespace-only highlights`
        );
        candidates.forEach((e) => {
            if (!e.classList.contains('iaa-zero-width') && !e.textContent?.trim()) {
                e.after(...e.childNodes);
                e.remove();
            }
        });
        const end = performance.now();
        console.log(`Removing whitespace only highlights took ${end - start}ms`);
    }

    private postProcessHighlights() {
        // Find all the highlights that belong to the same annotation (VID)
        const highlightsByVid = groupHighlightsByVid(this.getAllHighlights());

        // Add special CSS classes to the first and last highlight of each annotation
        for (const highlights of highlightsByVid.values()) {
            if (highlights.length) {
                if (annotatorState.showLabels) {
                    highlights.forEach((e) => e.classList.add('iaa-inline-label'));
                }
                highlights[0].classList.add('iaa-first-highlight');
                highlights[highlights.length - 1].classList.add('iaa-last-highlight');
            }
        }
    }

    private getAllHighlights() {
        return this.root.querySelectorAll('.iaa-highlighted');
    }

    private getAllTextMarkers() {
        return this.root.querySelectorAll('.iaa-text-marker');
    }

    // eslint-disable-next-line no-undef
    getHighlightsForAnnotation(vid: VID): NodeListOf<Element> {
        return this.root.querySelectorAll(`[data-iaa-id="${vid}"].iaa-highlighted`);
    }

    private renderTextMarker(doc: AnnotatedText, marker: TextMarker) {
        const range = offsetToRange(
            this.root,
            marker.offsets[0][0] + doc.window[0],
            marker.offsets[0][1] + doc.window[0]
        );

        if (!range) {
            console.debug('Could not render text marker: ' + marker);
            return;
        }

        const attributes = {
            class: `iaa-text-marker iaa-marker-${marker.type}`,
        };

        this.toCleanUp.add(
            safeHighlightText(
                range,
                annotatorState.protectElements ? this.protectedElementsMatcher : undefined,
                'mark',
                attributes
            )
        );
    }

    private renderSpanAnnotation(doc: AnnotatedText, span: Span) {
        const begin = span.offsets[0][0] + doc.window[0];
        const end = span.offsets[0][1] + doc.window[0];

        const classList = ['iaa-highlighted'];
        const ms = doc.annotationMarkers.get(span.vid) || [];
        ms.forEach((m) => classList.push(`iaa-marker-${m.type}`));

        if (begin === end) classList.push('iaa-zero-width');

        const hasError = span.comments?.find((comment) => comment.type === 'error');
        if (hasError) classList.push('iaa-marker-error');

        const hasInfo = span.comments?.find((comment) => comment.type === 'info');
        if (hasInfo) classList.push('iaa-marker-info');

        const styleList = [
            `--iaa-color: ${bgToFgColor(span.color || '#000000')}`,
            `--iaa-background-color: ${span.color || '#000000'}${this.alpha}`,
            `--iaa-border-color: ${span.color || '#000000'}`,
        ];

        var decorations = '';
        if (classList.includes('iaa-marker-info')) decorations += INFO_LABEL;
        if (classList.includes('iaa-marker-warn')) decorations += WARN_LABEL;
        if (classList.includes('iaa-marker-error')) decorations += ERROR_LABEL;
        if (decorations) decorations += ' ';

        let label = `${decorations}${span.label || `[${span.layer.name}]` || NO_LABEL}`;
        if (span.score && !span.hideScore) {
            label += ` [${span.score.toFixed(2)}]`;
        }

        const attributes = {
            'data-iaa-id': `${span.vid}`,
            'data-iaa-label': label,
            class: classList.join(' '),
            style: styleList.join('; '),
        };

        const viewportBegin = this.tracker.currentRange[0];
        const viewportEnd = this.tracker.currentRange[1];

        if (viewportBegin <= begin && end <= viewportEnd) {
            // Quick and easy if the annotation fits entirely into the visible viewport
            const startTime = performance.now();
            this.renderHighlight(span, begin, end, attributes);
            const endTime = performance.now();
            // console.debug(`Rendering span with size ${end - begin} took ${Math.abs(endTime - startTime)}ms`)
        } else {
            // Try optimizing for long spans to improve rendering performance
            let fragmentCount = 0;
            const startTime = performance.now();

            const coreBegin = Math.max(begin, viewportBegin);
            const coreEnd = Math.min(end, viewportEnd);
            if (coreBegin <= coreEnd) {
                this.renderHighlight(span, coreBegin, coreEnd, attributes);
                fragmentCount++;
            }

            attributes.class += ' iaa-zero-width'; // Prevent prefix/suffix fragmens from being cleaned up
            if (!(viewportBegin <= begin && begin <= viewportEnd)) {
                this.renderHighlight(span, begin, begin, attributes);
                fragmentCount++;
            }

            if (!(viewportBegin <= end && end <= viewportEnd)) {
                this.renderHighlight(span, end, end, attributes);
                fragmentCount++;
            }
            const endTime = performance.now();
            // console.debug(`Rendering span with size ${end - begin} took ${Math.abs(endTime - startTime)}ms (${fragmentCount} fragments)`)
        }
    }

    renderHighlight(span: Span, begin: number, end: number, attributes: { class: string }): void {
        const range = offsetToRange(this.root, begin, end);
        if (!range) {
            console.debug(
                `Could not determine fragment range for (${begin},${end}) of annotation ${span}`
            );
            return;
        }

        if (range.collapsed && !attributes.class.includes('iaa-zero-width')) {
            console.debug(
                `Fragment range for (${begin},${end}) of annotation ${span} is collapsed`
            );
            return;
        }

        this.toCleanUp.add(
            safeHighlightText(
                range,
                annotatorState.protectElements ? this.protectedElementsMatcher : undefined,
                'mark',
                attributes
            )
        );
    }

    private clearScrollMarkers() {
        if (this.scrollMutationObserver) {
            this.scrollMutationObserver.disconnect();
            this.scrollMutationObserver = undefined;
        }
        if (this.removeScrollMarkersTimeout) {
            this.scrolling = false;
            // Scroll-loop timers are real setTimeouts (not idle callbacks), so use
            // clearTimeout directly rather than going through cancelScheduled.
            window.clearTimeout(this.removeScrollMarkersTimeout);
            this.removeScrollMarkersTimeout = undefined;
        }
        // Always remove existing markers. Gating this on removeScrollMarkersTimeout
        // means the markers stick around after a scroll completes naturally (the
        // quiet-period timer clears itself just before calling scrollToComplete,
        // so the gate is false by the time we get here). The next scrollTo then
        // creates a second <mark id="iaa-scroll-marker"> alongside the stale one,
        // and the id-based lookup returns the first/stale match -- which is
        // already in view -- so no scroll occurs.
        this.removeScrollMarkers.forEach((remove) => remove());
        this.removeScrollMarkers = [];
    }

    private renderPingMarkers(pingRanges?: Offsets[]) {
        if (!pingRanges) return;

        console.log('Rendering ping markers');

        for (const pingOffset of pingRanges || []) {
            const pingRange = offsetToRange(this.root, pingOffset[0], pingOffset[1]);
            if (pingRange) {
                this.removePingMarkers.push(
                    safeHighlightText(
                        pingRange,
                        annotatorState.protectElements ? this.protectedElementsMatcher : undefined,
                        'mark',
                        {
                            class: 'iaa-ping-marker',
                        }
                    )
                );
            }
        }

        this.removeWhitepaceOnlyHighlights('.iaa-ping-marker');
        this.removeSpuriousZeroWidthHighlights();

        if (this.removePingMarkers.length > 0) {
            this.removePingMarkersTimeout = window.setTimeout(
                () => this.clearPingMarkers(),
                PING_MARKER_REMOVAL_DELAY_MS
            );
        }
    }

    private clearPingMarkers() {
        console.log('Clearing ping markers');

        if (this.removePingMarkersTimeout) {
            window.clearTimeout(this.removePingMarkersTimeout);
            this.removePingMarkersTimeout = undefined;
            this.removePingMarkers.forEach((remove) => remove());
            this.removePingMarkers = [];
        }
    }

    scrollTo(args: { offset: number; position?: string; pingRanges?: Offsets[] }): void {
        console.debug('Scrolling to offset ' + args.offset);

        const range = offsetToRange(this.root, args.offset, args.offset);
        if (!range) {
            console.debug('Could not determine scroll target range for offset ' + args.offset);
            return;
        }

        this.clearScrollMarkers();
        this.clearPingMarkers();

        // Add scroll marker
        const removeScrollMarker = safeHighlightText(
            range,
            annotatorState.protectElements ? this.protectedElementsMatcher : undefined,
            'mark',
            {
                id: 'iaa-scroll-marker',
            }
        );
        this.removeScrollMarkers = [removeScrollMarker];

        if (!annotatorState.showEmptyHighlights) {
            this.removeWhitepaceOnlyHighlights('.iaa-ping-marker');
        }

        this.root.querySelectorAll('.iaa-ping-marker').forEach((e) => {
            if (!e.textContent) e.remove();
        });

        // The scroll target may be hidden. In this case, we need to find the next visible element.
        let scrollTarget: Element | null = this.root.querySelector('#iaa-scroll-marker');
        if (scrollTarget !== null) {
            removeClassFromAncestors(scrollTarget, 'iaa-secluded', this.root);
        }
        while (scrollTarget !== null) {
            const targetStyle = window.getComputedStyle(scrollTarget);
            if (targetStyle.display === 'none' || targetStyle.visibility === 'hidden') {
                if (scrollTarget.nextElementSibling) {
                    scrollTarget = scrollTarget.nextElementSibling;
                } else {
                    scrollTarget = scrollTarget.parentElement;
                }
                continue;
            }
            break;
        }

        const finalScrollTarget = scrollTarget;
        if (finalScrollTarget) {
            // As part of scrolling and rendering, it may be possible that CSS scroll padding properties
            // are set on our scroll root element. For such a case, we schedule a new scroll action
            // which would then take the new padding into account. We do this as long as the transient
            // markers are still there.
            // Use real setTimeout (not the schedule()/requestIdleCallback abstraction)
            // for scroll-loop timing: requestIdleCallback fires as soon as the browser
            // is idle, treating its `timeout` argument as a max wait — useless for
            // implementing real delays between scroll iterations or a quiet period.
            var scrollIntoViewFunc = () => {
                this.removeScrollMarkersTimeout = undefined;
                finalScrollTarget.scrollIntoView({
                    behavior: 'auto',
                    block: 'center',
                    inline: 'nearest',
                });

                const wrapper = this.root.closest('.i7n-wrapper');
                const scrollTop = wrapper?.scrollTop || this.root.scrollTop || 0;
                this.lastMarkerTop = finalScrollTarget.getBoundingClientRect().top;
                if (scrollTop === this.lastScrollTop) {
                    // Scroll position is stable. Release the loader-deferral so any
                    // pending annotation render can run, then wait through a quiet
                    // period. If the render mutates the layout the MutationObserver
                    // will re-suspend scrolling and reschedule another iteration; if
                    // nothing mutates we complete.
                    this.scrolling = false;
                    this.removeScrollMarkersTimeout = window.setTimeout(() => {
                        this.removeScrollMarkersTimeout = undefined;
                        this.scrollToComplete(args.pingRanges);
                    }, SCROLL_QUIET_PERIOD_MS);
                } else {
                    this.lastScrollTop = scrollTop;
                    this.removeScrollMarkersTimeout = window.setTimeout(
                        scrollIntoViewFunc,
                        SCROLL_ITERATION_MS
                    );
                }
            };

            this.scrolling = true;
            this.sectionAnnotationVisualizer.suspend();
            this.sectionAnnotationCreator.suspend();
            this.relationVisualizer.suspend();
            // Grip anchors go stale as the content scrolls; clear and let hover re-derive them.
            this.relationGripLayer.clear();

            // Watch the scroll container for DOM mutations during scrolling. Async
            // renders (e.g. section summary spacers) can shift content above the
            // marker and push it out of the viewport. On any mutation we invalidate
            // the stability tracker and reschedule another iteration so the loop
            // re-scrolls to the marker's new position.
            const scrollContainer = this.root.closest('.i7n-wrapper') || this.root;
            this.scrollMutationObserver?.disconnect();
            this.scrollMutationObserver = new MutationObserver(() => {
                if (this.removeScrollMarkers.length === 0) return;
                // Filter out mutations that don't actually move the marker — e.g. a
                // re-render that clears and re-inserts the same section spacers in
                // the same positions, or a `root.normalize()` reshuffling text nodes
                // by sub-pixel amounts. Only react when the marker's viewport
                // position has shifted by at least one pixel.
                const markerTop = finalScrollTarget.getBoundingClientRect().top;
                if (
                    this.lastMarkerTop !== undefined &&
                    Math.abs(markerTop - this.lastMarkerTop) < 1
                ) {
                    return;
                }

                this.lastScrollTop = undefined;
                this.scrolling = true;
                if (this.removeScrollMarkersTimeout) {
                    window.clearTimeout(this.removeScrollMarkersTimeout);
                }
                this.removeScrollMarkersTimeout = window.setTimeout(
                    scrollIntoViewFunc,
                    SCROLL_ITERATION_MS
                );
            });
            this.scrollMutationObserver.observe(scrollContainer, {
                childList: true,
                subtree: true,
            });

            this.removeScrollMarkersTimeout = window.setTimeout(
                scrollIntoViewFunc,
                SCROLL_ITERATION_MS
            );
        }
    }

    getViewportScrollPosition(): ViewportScrollPosition | null {
        return this.syncController.getViewportScrollPosition();
    }

    scrollToViewportPosition(pos: ViewportScrollTarget): void {
        this.syncController.scrollToViewportPosition(pos);
    }

    isApplyingViewportSync(): boolean {
        return this.syncController.isApplyingViewportSync();
    }

    connectToHub(aHub: ViewportSyncPeer, aId: string, aEditor: AnnotationEditor): void {
        this.syncController.connectToHub(aHub, aId, aEditor);
    }

    disconnectFromHub(): void {
        this.syncController.disconnectFromHub();
    }

    private schedule(timeout: number, callback: () => void): number {
        if (typeof window.requestIdleCallback == 'undefined') {
            return window.setTimeout(callback, timeout);
        } else {
            return window.requestIdleCallback(callback, { timeout: timeout });
        }
    }

    private cancelScheduled(handle: number) {
        if (typeof window.cancelIdleCallback == 'undefined') {
            window.clearTimeout(handle);
        } else {
            window.cancelIdleCallback(handle);
        }
    }

    private scrollToComplete(pingRanges?: Offsets[]) {
        console.log('Scrolling complete');

        this.clearScrollMarkers();
        this.renderPingMarkers(pingRanges);
        this.root.normalize(); // https://github.com/apache/incubator-annotator/issues/120

        this.scrolling = false;
        this.sectionAnnotationCreator.resume();
        this.sectionAnnotationVisualizer.resume();
        this.relationVisualizer.resume();
        this.lastScrollTop = undefined;
        this.lastMarkerTop = undefined;
        this.syncController.reset();
    }

    private clearHighlights(): void {
        this.root.querySelectorAll('.iaa-vertical-marker-focus').forEach((e) => e.remove());

        if (!this.toCleanUp || this.toCleanUp.size === 0) {
            return;
        }

        const startTime = performance.now();
        const highlightCount = this.toCleanUp.size;
        this.toCleanUp.forEach((cleanup) => cleanup());
        this.toCleanUp.clear();
        this.root.normalize(); // https://github.com/apache/incubator-annotator/issues/120
        const endTime = performance.now();
        console.log(
            `Cleaning up ${highlightCount} annotations and normalizing DOM took ${Math.abs(endTime - startTime)}ms`
        );
    }

    destroy(): void {
        this.sectionAnnotationCreator.destroy();
        this.sectionAnnotationVisualizer.destroy();
        this.relationVisualizer.destroy();
        this.relationGripLayer.destroy();
        this.relationDragController.destroy();
        this.tracker.disconnect();
        this.sectionTracker.disconnect();
        this.scrollContainer?.removeEventListener('scroll', this.onContentScroll);
        if (this.relationScrollRaf !== undefined) cancelAnimationFrame(this.relationScrollRaf);
        this.clearHighlights();
    }
}
