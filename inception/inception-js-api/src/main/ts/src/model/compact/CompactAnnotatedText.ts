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
import type { Offsets } from '../Offsets';
import type { VID } from '../VID';
import type { CompactAnnotationMarker } from './CompactAnnotationMarker';
import type { CompactRelation } from './CompactRelation';
import type { CompactSpan } from './CompactSpan';
import type { CompactTextMarker } from './CompactTextMarker';

export interface CompactAnnotatedText {
    text?: string;
    window: Offsets;
    relations?: Array<CompactRelation>;
    spans?: Array<CompactSpan>;
    annotationMarkers?: Array<CompactAnnotationMarker>;
    textMarkers?: Array<CompactTextMarker>;
}

/**
 * Converts a list of {@link CompactAnnotationMarker}s to an easily accessible map using the {@link VID}
 * as key and the set of markers on that annotation as values.
 *
 * @param markerList a list of {@link CompactAnnotationMarker}s
 * @returns the map
 */
export function makeMarkerMap<T>(markerList: T[] | undefined): Map<VID, Array<T>> {
    const markerMap = new Map<VID, Array<T>>();
    if (markerList) {
        markerList.forEach((marker) => {
            marker[1].forEach((vid) => {
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
