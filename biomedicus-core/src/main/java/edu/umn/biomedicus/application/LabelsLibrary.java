/*
 * Copyright (c) 2016 Regents of the University of Minnesota.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.umn.biomedicus.application;

import com.google.inject.Inject;
import edu.umn.biomedicus.annotations.DocumentScoped;
import edu.umn.biomedicus.common.labels.Labeler;
import edu.umn.biomedicus.common.labels.Labels;

import java.util.Map;

@DocumentScoped
public class LabelsLibrary {
    private final Map<Class, Labels> labelsMap;
    private final Map<Class, Labeler> labelerMap;

    @Inject
    LabelsLibrary(Map<Class, Labels> labelsMap, Map<Class, Labeler> labelerMap) {
        this.labelsMap = labelsMap;
        this.labelerMap = labelerMap;
    }

    @SuppressWarnings("unchecked")
    public <T> Labels<T> labelsFor(Class<T> tClass) {
        return labelsMap.get(tClass);
    }

    @SuppressWarnings("unchecked")
    public <T> Labeler<T> labelerFor(Class<T> tClass) {
        return labelerMap.get(tClass);
    }

}