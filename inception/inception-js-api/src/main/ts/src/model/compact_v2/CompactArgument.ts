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
import type { VID, AnnotatedText } from '..';
import { Argument } from '..';

/**
 * Represents the endpoint of an arc.
 */
export type CompactArgument = [target: VID, label?: string];

export function unpackCompactArgument(
    doc: AnnotatedText,
    raw: CompactArgument
): Argument | undefined {
    const cooked = new Argument();
    cooked.targetId = raw[0];
    cooked.label = raw[1];
    cooked.target = doc.spans.get(cooked.targetId);
    if (!cooked.target) {
        console.warn(`Target ${cooked.targetId} not found`);
        return undefined;
    }
    return cooked;
}
