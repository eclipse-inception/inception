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
import type { AnnotatedText, Annotation } from '..';
import { Comment } from '..';

/**
 * Represents the endpoint of an arc.
 */
export type CompactComment = [comment: string, type?: 'I' | 'E'];

export function unpackCompactComments(
    doc: AnnotatedText,
    ann: Annotation,
    raws: CompactComment[] | undefined
): Comment[] {
    if (!raws) {
        return [];
    }

    const cookeds: Comment[] = [];
    for (const raw of raws) {
        const cooked = new Comment();
        cooked.targetId = ann.vid;
        cooked.target = ann;
        cooked.comment = raw[0];
        if (raw[1]) {
            switch (raw[1]) {
                case 'I':
                    cooked.type = 'info';
                    break;
                case 'E':
                    cooked.type = 'error';
                    break;
                default:
                    console.warn(`Unsupported comment type [${raw[1]}]`);
            }
        } else {
            cooked.type = 'info';
        }

        cookeds.push(cooked);
    }

    return cookeds;
}
