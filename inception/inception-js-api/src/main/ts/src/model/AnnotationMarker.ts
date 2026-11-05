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
import type { VID } from '.';
import type { MarkerType } from './Marker';

export class AnnotationMarker {
    type: MarkerType;
    vid: Array<VID>;
}

/**
 * Groups the given markers by the VID to which they apply.
 *
 * @param markerList a list of markers.
 * @returns the grouped markers.
 */
export function groupMarkers<T>(markerList: T[] | undefined): Map<VID, Array<T>> {
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
