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

import static org.junit.jupiter.api.extension.ConditionEvaluationResult.disabled;
import static org.junit.jupiter.api.extension.ConditionEvaluationResult.enabled;

import java.util.Set;

import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * @see EnabledIfAmd64ImagesCanRun
 */
public class Amd64ImagesCanRunCondition
    implements ExecutionCondition
{
    private static final Set<String> AMD64_ARCHITECTURES = Set.of("amd64", "x86_64");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext aContext)
    {
        var arch = System.getProperty("os.arch");

        if (AMD64_ARCHITECTURES.contains(arch)) {
            return enabled("Host architecture is " + arch);
        }

        if (OS.current() == OS.MAC) {
            return enabled("Docker Desktop on macOS emulates amd64 images on " + arch);
        }

        return disabled("amd64 images cannot run on " + OS.current() + "/" + arch);
    }
}
