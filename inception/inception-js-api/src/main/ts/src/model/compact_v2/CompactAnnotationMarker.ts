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
import type { VID } from '..';
import { AnnotationMarker } from '..';

export type CompactAnnotationMarker = [type: string, vid: Array<VID>];

export function unpackCompactAnnotationMarker(raw: CompactAnnotationMarker): AnnotationMarker {
    const cooked = new AnnotationMarker();
    cooked.type = raw[0];
    cooked.vid = raw[1];
    return cooked;
}
