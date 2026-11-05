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
import type { Offsets, VID, AnnotationMarker } from '..';
import { AnnotatedText } from '..';
import type { MarkerType } from '../Marker';
import type { CompactAnnotationMarker } from './CompactAnnotationMarker';
import { unpackCompactAnnotationMarker } from './CompactAnnotationMarker';
import type { CompactLayer } from './CompactLayer';
import { unpackCompactLayer } from './CompactLayer';
import type { CompactRelation } from './CompactRelation';
import { unpackCompactRelation } from './CompactRelation';
import type { CompactSpan } from './CompactSpan';
import { unpackCompactSpan } from './CompactSpan';
import type { CompactTextMarker } from './CompactTextMarker';
import { unpackCompactTextMarker } from './CompactTextMarker';

export interface CompactAnnotatedText {
    text?: string;
    layers?: Array<CompactLayer>;
    window: Offsets;
    relations?: Array<CompactRelation>;
    spans?: Array<CompactSpan>;
    annotationMarkers?: Array<CompactAnnotationMarker>;
    textMarkers?: Array<CompactTextMarker>;
}

export function unpackCompactAnnotatedText(raw: CompactAnnotatedText): AnnotatedText {
    const cooked = new AnnotatedText();
    cooked.text = raw.text;
    cooked.window = raw.window;
    raw.layers?.forEach((layer) => cooked.layers.set(layer.id, unpackCompactLayer(layer)));

    for (const span of raw.spans || []) {
        const cookedSpan = unpackCompactSpan(cooked, span);
        if (cookedSpan) {
            cooked.spans.set(cookedSpan.vid, cookedSpan);
        }
    }

    for (const rel of raw.relations || []) {
        const cookedRel = unpackCompactRelation(cooked, rel);
        if (cookedRel) {
            cooked.relations.set(cookedRel.vid, cookedRel);
        }
    }

    const annotationMarkers = raw.annotationMarkers?.map(unpackCompactAnnotationMarker);
    cooked.annotationMarkers = makeMarkerMap(annotationMarkers);
    cooked.markedAnnotations = makeMarkedAnnotationsMap(annotationMarkers);
    cooked.textMarkers = raw.textMarkers?.map(unpackCompactTextMarker) || [];
    return cooked;
}

function makeMarkedAnnotationsMap(annotationMarkers?: AnnotationMarker[]): Map<MarkerType, VID[]> {
    const markedAnnotations = new Map<MarkerType, VID[]>();
    if (annotationMarkers) {
        for (const marker of annotationMarkers) {
            for (const vid of marker.vid) {
                let ms = markedAnnotations.get(marker.type);
                if (!ms) {
                    ms = [];
                    markedAnnotations.set(marker.type, ms);
                }
                ms.push(vid);
            }
        }
    }
    return markedAnnotations;
}

/**
 * Converts a list of {@link CompactAnnotationMarker}s to an easily accessible map using the {@link VID}
 * as key and the set of markers on that annotation as values.
 *
 * @param markerList a list of {@link CompactAnnotationMarker}s
 * @returns the map
 */
export function makeMarkerMap(
    markerList: AnnotationMarker[] | undefined
): Map<VID, Array<AnnotationMarker>> {
    const markerMap = new Map<VID, Array<AnnotationMarker>>();
    if (markerList) {
        markerList.forEach((marker) => {
            marker.vid.forEach((vid) => {
                let ms = markerMap.get(vid);
                if (!ms) {
                    ms = [];
                    markerMap.set(vid, ms);
                }
                ms.push(marker);
            });
        });
    }
    return markerMap;
}
