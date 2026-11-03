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
package de.tudarmstadt.ukp.inception.recommendation.imls.external.util;

import static de.tudarmstadt.ukp.inception.support.json.JSONUtil.fromJsonStream;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Paths;

import org.apache.uima.cas.CAS;
import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.jcas.JCas;
import org.apache.uima.util.CasIOUtils;

import de.tudarmstadt.ukp.inception.recommendation.imls.external.v2.api.Document;

public class Fixtures
{

    public static CAS loadSmallCas() throws Exception
    {
        return loadCas("small_cas.xmi");

    }

    public static CAS loadAlaskaCas() throws Exception
    {
        return loadCas("alaska.xmi");
    }

    private static CAS loadCas(String aPathToXmi) throws Exception
    {
        try (FileInputStream fis = new FileInputStream(getResource(aPathToXmi))) {
            JCas jcas = JCasFactory.createJCas();
            CasIOUtils.load(fis, jcas.getCas());
            return jcas.getCas();
        }
    }

    public static Document loadSmallDocument() throws Exception
    {

        try (FileInputStream fis = new FileInputStream(getResource("small_cas.json"))) {
            return fromJsonStream(Document.class, fis);
        }
    }

    public static File getResource(String aResourceName)
    {
        return Paths.get("src", "test", "resources", aResourceName).toFile();
    }
}
