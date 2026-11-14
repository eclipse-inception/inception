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
package de.tudarmstadt.ukp.inception.support.test.docker;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Enables a test only where Docker can run images that exist for {@code linux/amd64} alone: on an
 * amd64 host, or on macOS with any architecture, because Docker Desktop emulates amd64 there. Linux
 * on arm (e.g. the arm CI runner) has no such emulation, so the test is disabled there.
 */
@Target({ TYPE, METHOD })
@Retention(RUNTIME)
@Documented
@ExtendWith(Amd64ImagesCanRunCondition.class)
public @interface EnabledIfAmd64ImagesCanRun
{
}
