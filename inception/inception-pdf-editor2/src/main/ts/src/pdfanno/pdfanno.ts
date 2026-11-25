import './pdfanno.css';
import * as textLayer from './page/textLayer';
import PDFAnnoPage from './page/pdf/PDFAnnoPage';
import { dispatchWindowEvent } from './shared/util';
import EventEmitter from 'events';
import AnnotationContainer from './core/src/model/AnnotationContainer';
import AbstractAnnotation from './core/src/model/AbstractAnnotation';
import { installSpanSelection } from './core/src/UI/span';
import { installRelationSelection } from './core/src/UI/relation';
import {
    unpackCompactAnnotatedTextV2,
    type DiamAjax,
    type Offsets,
    type ViewportScrollPosition,
    type ViewportScrollTarget,
    AnnotatedText,
    Span,
    Relation,
    TextMarker,
} from '@inception-project/inception-js-api';
import { type CompactAnnotatedText } from '@inception-project/inception-js-api/src/model/compact_v2';
import { type DiamLoadAnnotationsOptions } from '@inception-project/inception-js-api/src/diam/DiamAjax';
import SpanAnnotation from './core/src/model/SpanAnnotation';
import { getGlyphAtTextOffset, getGlyphsInRange } from './page/textLayer';
import RelationAnnotation from './core/src/model/RelationAnnotation';
import { createRect, mapToDocumentCoordinates, mergeRects } from './core/src/render/renderSpan';
import { Rectangle } from '../vmodel/Rectangle';
import AnnotationDetailPopOver from '@inception-project/inception-js-api/src/widget/AnnotationDetailPopOver.svelte';
import { mount, unmount } from 'svelte';
import { transform } from './core/src/UI/utils';
import { blendTowardScrollProgress } from './ViewportSyncUtils';

// TODO make it a global const.
// const svgLayerId = 'annoLayer'
export const annoLayer2Id = 'annoLayer2';

let annoPage: PDFAnnoPage;
let annotationContainer: AnnotationContainer;
let diamAjax: DiamAjax;
let currentFocusPage: number;
let pagechangeEventCounter: number;

let data: AnnotatedText | undefined;
let popover: AnnotationDetailPopOver;

export async function initPdfAnno(ajax: DiamAjax): Promise<void> {
    globalThis.globalEvent = new EventEmitter();
    globalThis.globalEvent.setMaxListeners(0);
    diamAjax = ajax;

    // Create an annocation container.
    annotationContainer = new AnnotationContainer();
    annoPage = new PDFAnnoPage(annotationContainer);

    // FIXME: These should be removed
    globalThis.annotationContainer = annotationContainer;

    // Enable a view mode.
    // UI.enableViewMode()

    const initPromise = new Promise<void>((resolve) => {
        if (!globalThis.PDFViewerApplication.initializedPromise) {
            return;
        }

        console.log('Waiting for the document to load...');
        globalThis.PDFViewerApplication.initializedPromise.then(function () {
            const app = globalThis.PDFViewerApplication;

            if (app.pdfViewer) {
                app.pdfViewer.annotationMode = 0; // DISABLE
            }

            // Disable PDF.js search and delegate to browser search
            delete app.supportsIntegratedFind;
            app.supportsIntegratedFind = true;

            // Disable PDF.js printing
            delete app.supportsPrinting;
            app.supportsPrinting = false;

            // The event called at page rendered by pdfjs.
            app.eventBus.on('pagerendered', (ev) => onPageRendered(ev));
            // Adapt to scale change.
            app.eventBus.on('scalechanged', (ev) => onScaleChange(ev));
            app.eventBus.on('zoomin', (ev) => onScaleChange(ev));
            app.eventBus.on('zoomout', (ev) => onScaleChange(ev));
            app.eventBus.on('zoomreset', (ev) => onScaleChange(ev));
            app.eventBus.on('sidebarviewchanged', (ev) => onScaleChange(ev));
            app.eventBus.on('resize', (ev) => onScaleChange(ev));

            const loadedCallback = () => {
                console.log('Document loaded...');
                app.eventBus.off('pagerendered', loadedCallback);
                resolve();
            };

            app.eventBus.on('pagerendered', loadedCallback);
        });
    });

    // Init viewer.
    annoPage.initializeViewer(undefined);

    // Start application.
    annoPage.startViewerApplication();

    installSpanSelection();
    installRelationSelection();

    popover = mount(AnnotationDetailPopOver, {
        target: document.body,
        props: {
            root: document.body,
            ajax: diamAjax,
        },
    });

    // Show a content.
    await displayViewer();

    return initPromise;
}

export function destroy() {
    if (popover) {
        unmount(popover);
    }
}

function onPageRendered(ev) {
    // console.log('pagerendered:', ev.pageNumber)

    // No action, if the viewer is closed.
    if (!globalThis.PDFViewerApplication.pdfViewer.getPageView(0)) {
        return;
    }

    adjustPageGaps();
    removePdfJsAnnotationLayers();
    removeAnnoLayer();
    renderAnno();
}

function onScaleChange(ev) {
    // No action, if the viewer is not ready.
    if (!globalThis.PDFViewerApplication.pdfViewer.getPageView(0)) {
        return;
    }

    adjustPageGaps();
    removeAnnoLayer();
    renderAnno();
}

function adjustPageGaps() {
    // Issue Fix.
    // Correctly rendering when changing scaling.
    // The margin between pages is fixed(9px), and never be scaled in default,
    // then manually have to change the margin.
    const scale = globalThis.PDFViewerApplication.pdfViewer.getPageView(0).viewport.scale;
    document.querySelectorAll('.page').forEach((e) => {
        const page = e as HTMLElement;
        const borderWidth = `${9 * scale}px`;
        page.style.borderTopWidth = borderWidth;
        page.style.borderBottomWidth = borderWidth;
        page.style.marginBottom = `${-9 * scale}px`;
        page.style.marginTop = `${1 * scale}px`;
    });
}

/*
 * Remove PDF.js native annotation layers (which contain external links, etc.)
 */
function removePdfJsAnnotationLayers() {
    document.querySelectorAll('.annotationLayer').forEach((layer) => {
        layer.remove();
    });
}

/*
 * Remove the annotation layer and the temporary rendering layer.
 */
function removeAnnoLayer() {
    // // TODO Remove #annoLayer.
    // document.getElementById(svgLayerId)?.remove()
    document.getElementById(annoLayer2Id)?.remove();
}

/*
 * Render annotations saved in the storage.
 */
function renderAnno() {
    // No action, if the viewer is closed.
    if (!globalThis.PDFViewerApplication.pdfViewer.getPageView(0)) {
        return;
    }

    // Check already exists.
    if (document.getElementById(annoLayer2Id)) return;

    const viewer = document.getElementById('viewer');
    if (!viewer) {
        console.error('Cannot render: no viewer element found.');
        return;
    }

    const pageElement = document.querySelector('.page');
    if (!pageElement) {
        console.error('Cannot render: no page element found.');
        return;
    }

    let leftMargin = (viewer.clientWidth - pageElement.clientWidth) / 2;

    // At window.width < page.width.
    if (leftMargin < 0) {
        leftMargin = 9;
    }

    const height = viewer.clientHeight;
    const width = pageElement.clientWidth;

    viewer.style.position = 'relative';

    let markerLayer = document.getElementById('markerLayer');
    if (!markerLayer) {
        markerLayer = document.createElement('div');
        markerLayer.id = 'markerLayer';
        markerLayer.classList.add('markerLayer');
        markerLayer.style.position = 'absolute';
        markerLayer.style.top = '0px';
        markerLayer.style.visibility = 'hidden';
        markerLayer.style.overflow = 'hidden';
        markerLayer.style.zIndex = '3';
        viewer.appendChild(markerLayer);
    }
    markerLayer.style.left = `${leftMargin}px`;
    markerLayer.style.width = `${width}px`;
    markerLayer.style.height = `${height}px`;

    const annoLayer2 = document.createElement('div');
    annoLayer2.id = annoLayer2Id;
    annoLayer2.classList.add('annoLayer');
    annoLayer2.style.position = 'absolute';
    annoLayer2.style.top = '0px';
    annoLayer2.style.left = `${leftMargin}px`;
    annoLayer2.style.width = `${width}px`;
    annoLayer2.style.height = `${height}px`;
    annoLayer2.style.visibility = 'hidden';
    annoLayer2.style.zIndex = '2';
    viewer.appendChild(annoLayer2);

    dispatchWindowEvent('annotationlayercreated');

    rerenderAnnotations();
}

/**
 * Render all annotations.
 */
function rerenderAnnotations() {
    const annotations = annotationContainer.getAllAnnotations();
    if (annotations.length === 0) {
        return;
    }

    annotations.forEach((a: AbstractAnnotation) => {
        a.render();
        a.enableViewMode();
    });

    const annoLayer2 = document.getElementById(annoLayer2Id);
    const markerLayer = document.getElementById('markerLayer');

    // Safari has a bug where it seems that elements starting out as hidden don't get rendered properly.
    // This hack is to force a reflow by making the element visible, then hiding it again.
    if (/^((?!chrome|android).)*safari/i.test(navigator.userAgent)) {
        console.log('Safari detected, forcing reflow');
        if (annoLayer2) {
            annoLayer2.style.visibility = 'visible';
        }
        if (markerLayer) {
            markerLayer.style.visibility = 'visible';
        }

        window.requestAnimationFrame(() => {
            if (annoLayer2) {
                annoLayer2.style.visibility = 'hidden';
            }
            if (markerLayer) {
                markerLayer.style.visibility = 'hidden';
            }
        });
    }

    dispatchWindowEvent('annotationrendered');
}

export function scrollTo(args: {
    offset: number;
    position?: string;
    pingRanges?: Offsets[];
}): void {
    const page = textLayer.findPageForTextOffset(args.offset)?.index;
    if (!page) {
        console.error(`No page found for offset: ${args.offset}`);
        return;
    }

    const pingRange: Offsets =
        args.pingRanges && args.pingRanges.length > 0
            ? args.pingRanges[0]
            : [args.offset, args.offset + 1];

    const rectangles = mapToDocumentCoordinates(getGlyphsInRange(pingRange).map((g) => g.bbox));
    const markerLayer = document.getElementById('markerLayer');
    if (!markerLayer) {
        console.error('No marker layer found.');
        return;
    }

    if (!rectangles?.length) {
        console.warn(`No glyphs found for ping range: ${pingRange} - scrolling only to page`);
        document.querySelector(`.page[data-page-number="${page}"]`)?.scrollIntoView();
        return;
    }

    markerLayer.querySelectorAll('.ping').forEach((ping) => ping.remove());

    let ping: HTMLElement = document.createElement('div');
    ping.classList.add('anno-span', 'ping');
    ping.style.zIndex = '10';
    rectangles.forEach((rect) => {
        const child = createRect(rect);
        child.classList.add('ping');
        child.animate([{ opacity: 0.75 }, { opacity: 0 }], { duration: 5000, iterations: 1 });
        ping.appendChild(child);
    });

    const viewport = globalThis.PDFViewerApplication.pdfViewer.getPageView(0).viewport;
    ping = transform(markerLayer, ping, viewport);
    markerLayer.appendChild(ping);
    ping.firstElementChild?.scrollIntoView({ behavior: 'auto', block: 'center', inline: 'center' });
    setTimeout(() => ping.remove(), 2000); // Need to defer so scrolling can complete
}

/**
 * @return the scrollable viewport container of the PDF.js viewer.
 */
export function getScrollContainer(): HTMLElement | null {
    return document.getElementById('viewer')?.parentElement ?? null;
}

/** @return the `.page` element for a 1-based page number, or null if it is not in the DOM. */
function getPageElement(pageNumber: number): HTMLElement | null {
    return document.querySelector<HTMLElement>(`.page[data-page-number="${pageNumber}"]`);
}

/**
 * @return the scroll distance a page occupies, i.e. the gap between this page's top and the next page's top, for the
 * last page the page's own box height.
 */
function getPageScrollDistance(pageNumber: number, aRect: DOMRect): number {
    const next = getPageElement(pageNumber + 1);
    if (next) {
        const distance = next.getBoundingClientRect().top - aRect.top;
        // Guard against a degenerate/zero reading (e.g. next page not yet laid out).
        if (distance > 0) return distance;
    }
    return aRect.height;
}

/**
 * @return the position of the the PDF page straddling the viewport top, and the fraction is the viewport
 * top's progress through that page's height.
 */
export function getViewportScrollPosition(): ViewportScrollPosition | null {
    const container = getScrollContainer();
    if (!container) return null;

    const containerTop = container.getBoundingClientRect().top;

    // The anchor page is the first `.page` whose bottom is below the viewport top - i.e. the page
    // the viewport top currently sits within. Pages render top-to-bottom in document order.
    const pageElements = Array.from(
        document.querySelectorAll<HTMLElement>('.page[data-page-number]')
    );
    let anchorElement: HTMLElement | undefined;
    for (const pageElement of pageElements) {
        if (pageElement.getBoundingClientRect().bottom > containerTop) {
            anchorElement = pageElement;
            break;
        }
    }
    if (!anchorElement) return null;

    const page = textLayer.getPage(Number(anchorElement.dataset.pageNumber));
    if (!page) return null;

    const rect = anchorElement.getBoundingClientRect();
    const distance = getPageScrollDistance(page.index, rect);
    const fraction =
        distance > 0 ? Math.min(1, Math.max(0, (containerTop - rect.top) / distance)) : 0;

    // Overall scroll progress lets the receiver blend toward its own extreme near the top/bottom
    // (see ViewportScrollPosition.scrollProgress), so scrolling just off an extreme no longer snaps.
    const maxScroll = container.scrollHeight - container.clientHeight;
    const scrollProgress = maxScroll > 0 ? container.scrollTop / maxScroll : 0;

    return { begin: page.range[0], end: page.range[1], fraction, scrollProgress };
}

export function scrollToViewportPosition(pos: ViewportScrollTarget): void {
    const container = getScrollContainer();
    if (!container) return;

    const page = textLayer.findPageForTextOffsetClamped(pos.begin);
    if (!page) return;

    const pageElement = getPageElement(page.index);
    if (!pageElement) return;

    const containerTop = container.getBoundingClientRect().top;
    const rect = pageElement.getBoundingClientRect();
    const fraction = Math.min(1, Math.max(0, pos.fraction));
    const distance = getPageScrollDistance(page.index, rect);

    // Interpolate position within the page's scroll distance
    const anchoredTop = container.scrollTop + (rect.top - containerTop) + fraction * distance;

    const maxScroll = container.scrollHeight - container.clientHeight;

    // Ensure smooth scrolling near the start and end of the document
    container.scrollTop = blendTowardScrollProgress(anchoredTop, pos.scrollProgress, maxScroll);
}

export function getAnnotations() {
    const focusPage = textLayer.getPage(currentFocusPage);

    if (!focusPage) {
        console.error(`Cannot find page ${currentFocusPage}`);
        return;
    }

    const pageBefore = textLayer.getPageBefore(currentFocusPage);
    const extendedBegin = (pageBefore || focusPage)?.range[0];
    const pageAfter = textLayer.getPageAfter(currentFocusPage);
    const extendedEnd = (pageAfter || focusPage)?.range[1];

    const options: DiamLoadAnnotationsOptions = {
        range: [extendedBegin, extendedEnd],
        includeText: false,
        clipSpans: true,
        clipArcs: false,
        longArcs: true,
        format: 'compact_v2',
    };

    diamAjax.loadAnnotations(options).then((doc: CompactAnnotatedText) => {
        data = unpackCompactAnnotatedTextV2(doc);
        renderAnnotations(data);
    });
}

function renderAnnotations(doc: AnnotatedText): void {
    const startTime = new Date().getTime();

    annotationContainer.clear();

    if (doc.spans) {
        console.log(`Loaded ${doc.spans.size} span annotations`, doc.spans);
        doc.spans.forEach((span) => renderSpan(doc, span));
    }

    if (doc.relations) {
        console.log(`Loaded ${doc.relations.size} relations annotations`);
        const stackBuckets = new Map<string, Relation[]>();
        doc.relations.forEach((relation) => {
            const a = relation.arguments[0].targetId;
            const b = relation.arguments[1].targetId;
            const key = String(a) < String(b) ? `${a}|${b}` : `${b}|${a}`;
            const bucket = stackBuckets.get(key);
            if (bucket) {
                bucket.push(relation);
            } else {
                stackBuckets.set(key, [relation]);
            }
        });
        stackBuckets.forEach((bucket) => {
            bucket.forEach((relation, index) =>
                renderRelation(doc, relation, index, bucket.length)
            );
        });
    }

    if (doc.textMarkers) {
        doc.textMarkers.forEach((marker) => makeTextMarker(doc, marker));
    }

    rerenderAnnotations();

    const endTime = new Date().getTime();
    console.log(`Client-side rendering took ${Math.abs(endTime - startTime)}ms`);
}

function renderSpan(doc: AnnotatedText, span: Span) {
    const begin = span.offsets[0][0] + doc.window[0];
    const end = span.offsets[0][1] + doc.window[0];
    const range: Offsets = [begin, end];

    const page = textLayer.findPageForTextOffset(begin)?.index;
    if (page === undefined) {
        console.warn(`No page found for span range ${range}`);
        return;
    }

    const rectangles = calculateRectangles(range);
    if (!rectangles) {
        return;
    }

    const spanAnnotation = new SpanAnnotation();
    spanAnnotation.source = span;
    spanAnnotation.vid = span.vid;
    spanAnnotation.textRange = range;
    spanAnnotation.page = page;
    spanAnnotation.color = span.color || '#FFF';
    spanAnnotation.text = span.label || '';
    spanAnnotation.rectangles = rectangles;

    const ms = doc.annotationMarkers.get(spanAnnotation.vid) || [];
    ms.forEach((m) => spanAnnotation.classList.push(`i7n-marker-${m.type}`));

    spanAnnotation.save();
}

function renderRelation(doc: AnnotatedText, relation: Relation, stackIndex = 0, stackSize = 1) {
    const source = annotationContainer.findById(relation.arguments[0].targetId);
    const target = annotationContainer.findById(relation.arguments[1].targetId);

    if (!source || !target) {
        console.warn(`Cannot find source or target for relation ${relation[0]}`);
        return;
    }

    const relationAnnotation = new RelationAnnotation();
    relationAnnotation.source = relation;
    relationAnnotation.vid = relation.vid;
    relationAnnotation.rel1Annotation = source as SpanAnnotation;
    relationAnnotation.rel2Annotation = target as SpanAnnotation;
    relationAnnotation.color = relation.color || '#FFF';
    relationAnnotation.text = relation.label || '';
    relationAnnotation.stackIndex = stackIndex;
    relationAnnotation.stackSize = stackSize;

    const ms = doc.annotationMarkers.get(relationAnnotation.vid) || [];
    ms.forEach((m) => relationAnnotation.classList.push(`i7n-marker-${m.type}`));

    relationAnnotation.save();
}

function makeTextMarker(doc: AnnotatedText, marker: TextMarker) {
    const begin = marker.offsets[0][0] + doc.window[0];
    const end = marker.offsets[0][1] + doc.window[0];
    const range: Offsets = [begin, end];

    const page = textLayer.findPageForTextOffset(begin)?.index;
    if (page === undefined) {
        console.warn(`No page found for marker range ${range}`);
        return;
    }

    const rectangles = calculateRectangles(range);
    if (!rectangles) {
        return;
    }

    const markerAnnotation = new SpanAnnotation();
    markerAnnotation.textRange = range;
    markerAnnotation.page = page;
    markerAnnotation.knob = false;
    markerAnnotation.border = false;
    markerAnnotation.rectangles = rectangles;
    markerAnnotation.classList = [`i7n-marker-${marker.type}`];
    markerAnnotation.save();
}

function calculateRectangles(range: [number, number]): Rectangle[] | null {
    let rectangles: Rectangle[];
    if (range[0] === range[1]) {
        const glyph = getGlyphAtTextOffset(range[0]);
        if (!glyph) {
            console.warn(`No glyph found for offset ${range[0]}`);
            return null;
        }
        rectangles = [
            new Rectangle({
                p: glyph.page,
                x: glyph.bbox.x,
                y: glyph.bbox.y,
                w: 3,
                h: glyph.bbox.h,
            }),
        ];
    } else {
        rectangles = mergeRects(getGlyphsInRange(range));
        if (!rectangles?.length) {
            console.warn(`No glyphs found for range ${range}`);
            return null;
        }
    }
    return rectangles;
}

async function displayViewer(): Promise<void> {
    // Display a PDF specified via URL query parameter.
    const params = new URL(document.URL).searchParams;
    const pdfUrl = params.get('pdf');
    const vModelUrl = params.get('vmodel');

    if (!vModelUrl) {
        console.error('No vmodel URL provided in query parameters');
        throw new Error('Visual model URL is required');
    }

    // Load a PDF file.
    return Promise.all([
        annoPage.loadVisualModel(vModelUrl),
        annoPage.displayViewer(getPDFName(pdfUrl), pdfUrl),
    ]).then(([vModel]) => {
        console.log('Loaded visual model and viewer');

        try {
            // Init textLayers.
            textLayer.setup(vModel);

            pagechangeEventCounter = 0;
            currentFocusPage = 1;
            const initAnnotations = function (e) {
                try {
                    getAnnotations();
                } finally {
                    globalThis.PDFViewerApplication.eventBus.off('pagerendered', initAnnotations);
                }
            };
            globalThis.PDFViewerApplication.eventBus.on('pagerendered', initAnnotations);
            globalThis.PDFViewerApplication.eventBus.on('pagechanging', function (e) {
                pagechangeEventCounter++;
                if (e.pageNumber !== currentFocusPage) {
                    const snapshot = pagechangeEventCounter;
                    const renderTimeout = 500;
                    setTimeout(() => {
                        if (
                            snapshot === pagechangeEventCounter &&
                            e.pageNumber !== currentFocusPage
                        ) {
                            currentFocusPage = e.pageNumber;
                            pagechangeEventCounter = 0;
                            getAnnotations();
                        }
                    }, renderTimeout);
                }
            });
        } catch (err) {
            console.error('Failed to analyze the PDF.', err);

            // Init viewer.
            annoPage.initializeViewer();
            // Start application.
            annoPage.startViewerApplication();
        }
    });
}

/**
 * Get a PDF name from URL.
 */
function getPDFName(url) {
    const a = url.split('/');
    return a[a.length - 1];
}
