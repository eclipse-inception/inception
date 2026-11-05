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
import type { Annotation, LazyDetailGroup, Offsets, VID } from '../model';

export type DiamLoadAnnotationsOptions = {
    format?: string;
    range?: Offsets;
    includeText?: boolean;
    clipSpans?: boolean;
    clipArcs?: boolean;
    longArcs?: boolean;
};

export type DiamSelectAnnotationOptions = {
    scrollTo?: boolean;
};

export interface DiamAjaxConnectOptions {
    url: string;
    csrfToken: string;
}

export interface DiamAjax {
    /**
     * Select the given annotation.
     *
     * This will generally trigger a re-rendering of the annotation detail sidebar by the server.
     *
     * @param id the annotation ID
     * @param options options like whether to scroll the annotation into view
     */
    selectAnnotation(id: VID, options?: DiamSelectAnnotationOptions): void;

    /**
     * Scroll to the given annotation or offset. If both are given, the offset is used. Offsets
     * are relative to the entire document, not to the current viewport.
     */
    scrollTo(args: { id?: VID; offset?: Offsets; offsets?: Array<Offsets> }): void;

    /**
     * Delete the given annotation.
     *
     * This will generally trigger a re-rendering of the document triggered by the server.
     *
     * @param id the annotation ID
     */
    deleteAnnotation(id: VID): void;

    /**
     * Create a new span annotation at the given loction.
     *
     * This will generally trigger a re-rendering of the document triggered by the server.
     *
     * @param offsets the position of the new annotation. Note that currently only a single offset is
     * supported.
     * @param spanText the text of the new annotation. This is deprecated and will be removed in the
     * future.
     */
    createSpanAnnotation(offsets: Array<Offsets>, spanText?: string): void;

    /**
     * Move the given span annotation to a new location.
     *
     * This will generally trigger a re-rendering of the document triggered by the server.
     *
     * @param id the annotation ID
     * @param offsets the position of the new annotation. Note that currently only a single offset is supported.
     */
    moveSpanAnnotation(id: VID, offsets: Array<Offsets>): void;

    /**
     * Create a new relation annotation between the two given spans.
     *
     * @param originSpanId the ID of the origin span
     * @param targetSpanId the ID of the target span
     * @param evt the mouse event that triggered the relation creation. The mouse position from the
     * event is used by the server to determine where to display the context menu if necessary.
     */
    createRelationAnnotation(originSpanId: VID, targetSpanId: VID, evt: MouseEvent): void;

    /**
     * Load annotations from the server. In the options, you can specify the format of the annotations.
     * The controls the kind of data that is provided to the promise.
     *
     * This method can be used by the editor e.g. to load an entire document or only  the currently
     * visible annotations when scrolling through a document.
     *
     * @param options options controlling e.g. the format of the annotations
     * @returns a promise that resolves to the loaded annotations in the specified format
     */
    loadAnnotations(options?: DiamLoadAnnotationsOptions): Promise<any>;

    /**
     * Tell the server to refresh the currently visible annotations and show any pending suggestions.
     *
     * @returns a promise that resolves when the refresh is complete
     */
    refreshAnnotations(): Promise<void>;

    /**
     * Load the lazy details of the given annotation.
     *
     * @param ann either the VID or the annotation itself
     * @param layerId the layer ID of the annnotation if the annotation is specified as a VID
     */
    loadLazyDetails(ann: VID | Annotation, layerId?: number): Promise<LazyDetailGroup[]>;

    /**
     * Load the preferences stored under the given key. This must be a key assigned to the editor
     * by the server during initialization.
     *
     * @param key the key of the preferences
     * @see {@link ../editor/AnnotationEditorProperties.ts}
     * @returns a promise that resolves to a JSON-fiable object with the preferences
     */
    loadPreferences(key: string): Promise<any>;

    /**
     * Store preferences under the given key. This must be a key assigned to the editor by the
     * server during initialization.
     *
     * @param key the key of the preferences
     * @param data a JSON-fiable object with the preferences
     */
    savePreferences(key: string, data: Record<string, any>): Promise<void>;

    /**
     * Trigger an extension action with the given ID. This is typically bound to left-double-click
     * events on an annotation.
     *
     * @param id the ID of the extension action
     */
    triggerExtensionAction(id: VID): void;

    /**
     * Open the context menu for the given annotation. The implementation of this context menu is
     * on the server side.
     *
     * @param id the ID of the annotation
     * @param evt the mouse event that triggered the context menu. The mouse position from the event
     * is used by the server to determine where to display the context menu.
     */
    openContextMenu(id: VID, evt: MouseEvent): void;
}
