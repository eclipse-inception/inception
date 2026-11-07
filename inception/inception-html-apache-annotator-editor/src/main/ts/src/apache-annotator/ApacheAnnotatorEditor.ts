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
    type DiamAjax,
    type DocumentStructureStrategy,
    type Offsets,
    type TocLevel,
    type ViewportScrollPosition,
    type ViewportScrollTarget,
    type ViewportSyncPeer,
    buildDocumentStructure,
    calculateStartOffset,
} from '@inception-project/inception-js-api';
import DocumentStructureNavigator from '@inception-project/inception-js-api/src/documentStructure/DocumentStructureNavigator.svelte';
import { ApacheAnnotatorVisualizer } from './ApacheAnnotatorVisualizer.svelte';
import { ApacheAnnotatorSelector } from './ApacheAnnotatorSelector';
import { normalizeSectionsForPinning } from './SectionPinning';
import ApacheAnnotatorToolbar from './ApacheAnnotatorToolbar.svelte';
import {
    annotatorState,
    defaultAnnotatorPreferences as defaultPreferences,
    LINE_SPACINGS,
    type LineSpacing,
} from './ApacheAnnotatorState.svelte';
import AnnotationDetailPopOver from '@inception-project/inception-js-api/src/widget/AnnotationDetailPopOver.svelte';
import { mount, tick, unmount } from 'svelte';
import {
    highlights,
    distinctHighlightStack,
    isPointOnInlineLabel,
    compileNsSelector,
    closestWithMatcher,
    type SelectionLike,
    expandSelectionOverProtectedElements,
} from './Utilities';
import { KeyboardEditorMode } from './KeyboardEditorMode';

export class ApacheAnnotatorEditor implements AnnotationEditor {
    private ajax: DiamAjax;
    private root: Element;
    private vis: ApacheAnnotatorVisualizer;
    private selector: ApacheAnnotatorSelector;
    private toolbar: ApacheAnnotatorToolbar;
    private popover: AnnotationDetailPopOver;
    private sectionSelector: string;
    private horizSplitPane: HTMLElement;
    private documentStructureNavigator: ReturnType<typeof mount> | undefined;
    private userPreferencesKey: string;
    private navigatorContainer: HTMLElement;
    private deferredInitializationSteps: (() => void)[] = [];
    private initializationComplete = false;
    private viewportSyncHub?: ViewportSyncPeer;
    private viewportSyncId?: string;
    private protectedElements: Set<string>;
    private protectedElementsMatcher?: (el: Element) => boolean;
    private activeResizeCleanup: (() => void) | undefined = undefined;
    private documentStructure: DocumentStructureStrategy;
    private documentContainer: HTMLElement | undefined = undefined;
    private tocRoot: TocLevel | undefined = undefined;
    private keyboardMode: KeyboardEditorMode | undefined = undefined;

    public constructor(
        element: Element,
        ajax: DiamAjax,
        userPreferencesKey: string,
        protectedElements: Set<string>,
        sectionElementLocalNames: Set<string>,
        documentStructure: DocumentStructureStrategy
    ) {
        this.ajax = ajax;
        this.root = element;
        this.userPreferencesKey = userPreferencesKey;
        // Settled during init by normalizeSectionsForPinning.
        this.sectionSelector = '';
        this.protectedElements = protectedElements;
        this.documentStructure = documentStructure;
        const protectedSel = [...protectedElements].join(',');
        this.protectedElementsMatcher = compileNsSelector(protectedSel) || undefined;

        let preferences = Object.assign({}, defaultPreferences);

        ajax.loadPreferences(userPreferencesKey)
            .then((p) => {
                preferences = Object.assign(preferences, defaultPreferences, p);
                console.log('Loaded preferences', preferences);

                annotatorState.showLabels = preferences.showLabels ?? defaultPreferences.showLabels;
                annotatorState.showRelationLabels =
                    preferences.showRelationLabels ?? defaultPreferences.showRelationLabels;
                annotatorState.showAggregatedLabels =
                    preferences.showAggregatedLabels ?? defaultPreferences.showAggregatedLabels;
                annotatorState.showEmptyHighlights =
                    preferences.showEmptyHighlights ?? defaultPreferences.showEmptyHighlights;
                annotatorState.showDocumentStructure =
                    preferences.showDocumentStructure ?? defaultPreferences.showDocumentStructure;
                annotatorState.showImages = preferences.showImages ?? defaultPreferences.showImages;
                annotatorState.showTables = preferences.showTables ?? defaultPreferences.showTables;
                annotatorState.documentStructureWidth =
                    preferences.documentStructureWidth ?? defaultPreferences.documentStructureWidth;
                annotatorState.protectElements =
                    preferences.protectElements ?? defaultPreferences.protectElements;
                annotatorState.keyboardCursorEnabled =
                    preferences.keyboardCursorEnabled ?? defaultPreferences.keyboardCursorEnabled;
                const loadedLineSpacing = preferences.lineSpacing ?? defaultPreferences.lineSpacing;
                annotatorState.lineSpacing = LINE_SPACINGS.includes(
                    loadedLineSpacing as LineSpacing
                )
                    ? (loadedLineSpacing as LineSpacing)
                    : 'mid';
                this.applyLineSpacing();
            })
            .then(() => {
                const documentContainer = this.root.ownerDocument.createElement('div');
                documentContainer.classList.add('iaa-document-container');
                [...this.root.ownerDocument.body.children].forEach((child) =>
                    documentContainer.appendChild(child)
                );

                // Make section elements pinnable and settle the section selector. This must run
                // before the IDs are assigned so they land on the wrapper elements rather than the
                // soon-to-be-buried originals.
                this.sectionSelector = normalizeSectionsForPinning(
                    documentContainer,
                    sectionElementLocalNames
                );
                this.ensureSectionElementsHaveAnId();

                this.documentStructure.preprocess(documentContainer);
                this.tocRoot = buildDocumentStructure(documentContainer, this.documentStructure);

                this.navigatorContainer = this.root.ownerDocument.createElement('div');
                this.navigatorContainer.classList.add('iaa-document-navigator');

                this.horizSplitPane = this.createHorizontalSplitPane(
                    this.navigatorContainer,
                    documentContainer
                );

                this.root.ownerDocument.body.appendChild(this.horizSplitPane);

                // The selector is created first so the visualizer's relation-drag controller can
                // defer multi-hit target disambiguation to it.
                this.selector = new ApacheAnnotatorSelector(this.root, this.ajax);
                this.vis = new ApacheAnnotatorVisualizer(
                    this.root,
                    this.ajax,
                    this.sectionSelector,
                    (clientX, clientY, targets, onPick) =>
                        this.selector.pickRelationTarget(clientX, clientY, targets, onPick)
                );
                this.vis.protectedElementSelector = [...protectedElements].join(',');

                this.vis.setSyncDocumentStructure({
                    sectionSelector: () => this.documentStructure.sectionSelector,
                    extractKey: (section) => this.documentStructure.extractKey(section),
                    tocRoot: () => this.tocRoot,
                });

                this.documentContainer = documentContainer;

                this.documentStructureNavigator = this.createDocumentNavigator(
                    this.navigatorContainer,
                    documentContainer,
                    this.documentStructure,
                    this.tocRoot
                );
                this.toolbar = this.createToolbar();

                this.keyboardMode = new KeyboardEditorMode(
                    this.root,
                    documentContainer,
                    this.ajax,
                    this.protectedElementsMatcher,
                    this.selector
                );
                this.keyboardMode.enable(annotatorState.keyboardCursorEnabled);

                this.popover = mount(AnnotationDetailPopOver, {
                    target: this.root.ownerDocument.body,
                    props: {
                        root: this.root,
                        ajax: this.ajax,
                    },
                });

                this.root.addEventListener('mouseup', (e) => this.onMouseUp(e));

                this.root.addEventListener('contextmenu', (e) => this.onRightClick(e));

                // Prevent right-click from triggering a selection event
                this.root.addEventListener('mousedown', (e) => this.cancelRightClick(e), {
                    capture: true,
                });
                this.root.addEventListener('mouseup', (e) => this.cancelRightClick(e), {
                    capture: true,
                });
                this.root.addEventListener('mouseclick', (e) => this.cancelRightClick(e), {
                    capture: true,
                });
            })
            .then(() => {
                this.deferredInitializationSteps.forEach((fn) => fn());
                this.deferredInitializationSteps = [];
            })
            .then(() => {
                this.initializationComplete = true;
                this.registerViewportSync();
            });
    }

    /**
     * Connect to the scroll-sync hub if a connection has been requested and the visualizer is ready.
     * The visualizer's sync controller owns the actual registration (it knows the scroll container);
     * the editor only gates on lifecycle - registration must wait until the viewport wrapper exists.
     */
    private registerViewportSync(): void {
        if (!this.viewportSyncHub || !this.viewportSyncId || !this.initializationComplete) {
            return;
        }
        this.vis?.connectToHub(this.viewportSyncHub, this.viewportSyncId, this);
    }

    /**
     * Set up a wrapper around the editor content and move the root content node into the wrapper
     * The wrapper creates the opportunity to add the document structure sidebar besides the
     * document content.
     */
    private createHorizontalSplitPane(leftPane: HTMLElement, rightPane: HTMLElement): HTMLElement {
        const divider = this.root.ownerDocument.createElement('div');
        const pane = document.createElement('div');
        pane.classList.add('iaa-split-pane');
        pane.appendChild(leftPane);
        pane.appendChild(divider);
        pane.appendChild(rightPane);

        let origin = 0;
        let totalWidth = 0;
        let leftStartWidth = 0;
        let glass: HTMLElement | undefined = undefined;
        let currentDelta = 0;

        divider.classList.add('iaa-divider');
        divider.addEventListener('mousedown', (e) => {
            if (e.buttons !== 1) return;

            if (this.activeResizeCleanup) {
                this.activeResizeCleanup();
            }

            origin = e.clientX;
            totalWidth = pane.getBoundingClientRect().width;
            leftStartWidth = leftPane.getBoundingClientRect().width;
            currentDelta = 0;
            glass = document.createElement('div');
            glass.classList.add('iaa-glass');

            const finishResize = () => {
                glass?.remove();
                glass = undefined;
                window.removeEventListener('mouseup', finishResize, true);
                this.activeResizeCleanup = undefined;

                // Apply the final resize when mouse is released
                const delta = currentDelta;
                let relativeWidth =
                    Math.max(0, Math.min(leftStartWidth + delta, totalWidth)) / totalWidth;
                relativeWidth = Math.max(0.1, Math.min(0.9, relativeWidth));
                let leftWidth = relativeWidth * totalWidth;
                leftPane.style.width = `${leftWidth}px`;
                leftPane.style.minWidth = `${leftWidth}px`;
                leftPane.style.maxWidth = `${leftWidth}px`;
                annotatorState.documentStructureWidth = relativeWidth;

                // Reset divider transform
                divider.style.transform = '';
            };

            // Listen for mouseup globally to catch releases outside the iframe
            window.addEventListener('mouseup', finishResize, true);
            this.activeResizeCleanup = finishResize;

            glass.addEventListener(
                'mousemove',
                (e) => {
                    if (e.buttons !== 1) {
                        finishResize();
                        return;
                    }

                    currentDelta = e.clientX - origin;
                    const delta = currentDelta;
                    let relativeWidth =
                        Math.max(0, Math.min(leftStartWidth + delta, totalWidth)) / totalWidth;
                    relativeWidth = Math.max(0.1, Math.min(0.9, relativeWidth));
                    divider.style.transform = `translateX(${relativeWidth * totalWidth - leftStartWidth}px)`;
                },
                true
            );
            this.root.ownerDocument.body.appendChild(glass);
        });

        return pane;
    }

    private ensureSectionElementsHaveAnId() {
        if (this.sectionSelector) {
            this.root.querySelectorAll(this.sectionSelector).forEach((e, i) => {
                if (!e.id) {
                    e.id = `i7n-sec-${i}`;
                }
            });
        }
    }

    private createToolbar(): ApacheAnnotatorToolbar {
        // Svelte components are appended to the target element. However, we want the toolbar to come
        // first in the DOM, so we first create a container element and prepend it to the body.
        const toolbarContainer = this.root.ownerDocument.createElement('div');
        toolbarContainer.style.position = 'sticky';
        toolbarContainer.style.top = '0px';
        toolbarContainer.style.zIndex = '10000';
        toolbarContainer.style.backgroundColor = '#fff';
        this.root.ownerDocument.body.insertBefore(
            toolbarContainer,
            this.root.ownerDocument.body.firstChild
        );

        return mount(ApacheAnnotatorToolbar, {
            target: toolbarContainer,
            props: {
                userPreferencesKey: this.userPreferencesKey,
                ajax: this.ajax,
                sectionSelector: this.sectionSelector,
            },
            events: {
                renderingPreferencesChanged: (e: Event) => {
                    this.loadAnnotations();
                },
                cssRenderingPreferencesChanged: (e: Event) => {
                    if (annotatorState.showImages) {
                        this.root.classList.remove('iaa-hide-images');
                    } else {
                        this.root.classList.add('iaa-hide-images');
                    }

                    if (annotatorState.showTables) {
                        this.root.classList.remove('iaa-hide-tables');
                    } else {
                        this.root.classList.add('iaa-hide-tables');
                    }
                },
                documentStructurePreferencesChanged: (e: Event) => {
                    this.navigatorContainer.style.display = annotatorState.showDocumentStructure
                        ? 'flex'
                        : 'none';
                    tick().then(() => {
                        const totalWidth = this.horizSplitPane.getBoundingClientRect().width;
                        const width =
                            totalWidth *
                            Math.max(0.1, Math.min(0.9, annotatorState.documentStructureWidth));
                        this.navigatorContainer.style.width = `${width}px`;
                        this.navigatorContainer.style.minWidth = `${width}px`;
                        this.navigatorContainer.style.maxWidth = `${width}px`;
                    });
                },
                keyboardCursorPreferencesChanged: (e: Event) => {
                    this.keyboardMode?.enable(annotatorState.keyboardCursorEnabled);
                },
                lineSpacingPreferencesChanged: (e: Event) => {
                    this.applyLineSpacing();
                    // The new line-height reflows the text, moving every highlight. Re-materialize
                    // the locked section dimensions and re-render so arcs/stubs follow the new
                    // positions. Only the geometry changed — the annotation data is unchanged — so
                    // re-render from the cached data instead of a full server round-trip.
                    this.vis?.optimizeLayout('line spacing');
                    this.vis?.rerender();
                },
                relationLabelPreferencesChanged: (e: Event) => {
                    // Pure visual toggle — the labels are already drawn, so just flip the mode.
                    this.vis?.refreshRelationLabelMode();
                },
            },
        });
    }

    /**
     * Apply the active line-spacing preset as a class on the editor root, matching the
     * class-toggle pattern used by the other visual preferences (e.g. `iaa-hide-images`).
     * The heights live in SCSS; `line-height` inherits down to the document content.
     */
    private applyLineSpacing(): void {
        const root = this.root as HTMLElement;
        LINE_SPACINGS.forEach((s) => root.classList.remove(`iaa-line-spacing-${s}`));
        const spacing = LINE_SPACINGS.includes(annotatorState.lineSpacing)
            ? annotatorState.lineSpacing
            : 'mid';
        root.classList.add(`iaa-line-spacing-${spacing}`);
    }

    private createDocumentNavigator(
        target: HTMLElement,
        documentContainer: HTMLElement,
        structure: DocumentStructureStrategy,
        tocRoot: TocLevel
    ) {
        return mount(DocumentStructureNavigator, {
            target,
            props: {
                documentContainer,
                structure,
                tocRoot,
            },
        });
    }

    private cancelRightClick(e: Event): void {
        if (e instanceof MouseEvent) {
            if (e.button === 2) {
                e.preventDefault();
                e.stopPropagation();
            }
        }
    }

    onMouseUp(event: Event): void {
        const sel = window.getSelection();
        if (!sel) return;

        if (sel.isCollapsed) {
            if (!this.selector.isVisible()) {
                this.selector.showSelector(event);
            }
            return;
        }

        let safeSel: SelectionLike;
        if (!annotatorState.protectElements || !sel.anchorNode || !sel.focusNode) {
            safeSel = {
                anchorNode: sel.anchorNode,
                anchorOffset: sel.anchorOffset,
                focusNode: sel.focusNode,
                focusOffset: sel.focusOffset,
            };
        } else {
            safeSel = expandSelectionOverProtectedElements(sel, this.protectedElementsMatcher);
        }

        if (!safeSel.anchorNode || !safeSel.focusNode) return;
        sel.removeAllRanges();

        const anchorOffset =
            calculateStartOffset(this.root, safeSel.anchorNode) + safeSel.anchorOffset;
        const focusOffset =
            calculateStartOffset(this.root, safeSel.focusNode) + safeSel.focusOffset;
        const begin = Math.min(anchorOffset, focusOffset);
        const end = Math.max(anchorOffset, focusOffset);
        this.ajax.createSpanAnnotation([[begin, end]], '');
    }

    onRightClick(event: Event): void {
        if (!(event instanceof MouseEvent) || !(event.target instanceof Node)) return;

        const hls = highlights(event.target);
        if (hls.length === 0) return;

        // If the user shift-right-clicks, open the normal browser context menu. This is useful
        // e.g. during debugging / developing
        if (event.shiftKey) return;

        event.preventDefault();

        // Like a left click: act directly if there is only one annotation or if the user clicked
        // on an inline label, otherwise let the user pick which annotation the menu is for.
        const point = { x: event.clientX, y: event.clientY };
        const stack = distinctHighlightStack(event.target);
        const direct =
            stack.length === 1 ? stack[0] : stack.find((hl) => isPointOnInlineLabel(point, hl));
        if (direct) {
            const vid = direct.getAttribute('data-iaa-id');
            if (vid) this.ajax.openContextMenu(vid, event);
            return;
        }

        this.selector.pickContextMenuTarget(event.clientX, event.clientY, stack, (vid) =>
            this.ajax.openContextMenu(vid, event)
        );
    }

    loadAnnotations(): void {
        this.vis?.loadAnnotations();
    }

    scrollTo(args: { offset: number; position?: string; pingRanges?: Offsets[] }): void {
        if (!this.initializationComplete) {
            this.deferredInitializationSteps.push(() => {
                this.vis.scrollTo(args);
                // Keep the keyboard caret with the jump target so the next cursor-key
                // press continues from here instead of scrolling the view back.
                this.keyboardMode?.moveCaretToOffset(args.offset);
            });
            return;
        }

        this.vis?.scrollTo(args);
        this.keyboardMode?.moveCaretToOffset(args.offset);
    }

    getViewportScrollPosition(): ViewportScrollPosition | null {
        if (!this.initializationComplete) return null;
        return this.vis?.getViewportScrollPosition() ?? null;
    }

    scrollToViewportPosition(pos: ViewportScrollTarget): void {
        if (!this.initializationComplete) return;
        this.vis?.scrollToViewportPosition(pos);
    }

    isApplyingViewportSync(): boolean {
        return this.vis?.isApplyingViewportSync() ?? false;
    }

    connectViewportSync(aHub: ViewportSyncPeer, aId: string): void {
        this.viewportSyncHub = aHub;
        this.viewportSyncId = aId;
        // Connects now if the visualizer is ready; otherwise the init chain connects once the
        // viewport wrapper exists (see registerViewportSync).
        this.registerViewportSync();
    }

    disconnectViewportSync(): void {
        this.vis?.disconnectFromHub();
        this.viewportSyncHub = undefined;
        this.viewportSyncId = undefined;
    }

    destroy(): void {
        this.disconnectViewportSync();

        // Clean up any active resize operation
        if (this.activeResizeCleanup) {
            this.activeResizeCleanup();
            this.activeResizeCleanup = undefined;
        }

        if (this.popover) {
            unmount(this.popover);
            this.popover = undefined;
        }

        if (this.toolbar) {
            unmount(this.toolbar);
            this.toolbar = undefined;
        }

        if (this.documentStructureNavigator) {
            unmount(this.documentStructureNavigator);
            this.documentStructureNavigator = undefined;
        }

        this.vis?.destroy();
        this.selector?.destroy();
        try {
            this.keyboardMode?.destroy();
        } catch {}
    }
}
