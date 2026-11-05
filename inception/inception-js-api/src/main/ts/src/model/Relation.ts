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
import type { Annotation, VID, Argument, Layer, Comment, AnnotatedText } from '.';

export class Relation implements Annotation {
    document: AnnotatedText;
    layer: Layer;
    vid: VID;
    color?: string;
    label?: string;
    score?: number;
    hideScore: boolean;
    comments?: Comment[];
    arguments: Array<Argument>;

    constructor(other?: Relation) {
        if (other) {
            this.document = other.document;
            this.layer = other.layer;
            this.vid = other.vid;
            this.color = other.color;
            this.label = other.label;
            this.comments = other.comments;
            this.arguments = other.arguments;
            this.hideScore = other.hideScore;
        }
    }
}
