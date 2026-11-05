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
import type { Offsets, VID, AnnotatedText } from '..';
import { Span } from '..';
import { unpackCompactComments } from './CompactComment';
import type { CompactSpanAttributes } from './CompactSpanAttributes';

export type CompactSpan = [
    layerId: number,
    vid: VID,
    offsets: Array<Offsets>,
    attributes?: CompactSpanAttributes,
];

export function unpackCompactSpan(doc: AnnotatedText, raw: CompactSpan): Span {
    const cooked = new Span();
    cooked.document = doc;
    cooked.layer = doc.__getOrCreateLayer(raw[0]);
    cooked.vid = raw[1];
    cooked.offsets = raw[2];
    cooked.color = raw[3]?.c;
    cooked.label = raw[3]?.l;
    cooked.score = raw[3]?.s;
    cooked.hideScore = raw[3]?.hs ? true : false;
    cooked.clippingFlags = raw[3]?.cl;
    cooked.comments = unpackCompactComments(doc, cooked, raw[3]?.cm);
    return cooked;
}
