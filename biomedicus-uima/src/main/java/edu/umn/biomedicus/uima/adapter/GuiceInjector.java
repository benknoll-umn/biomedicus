/*
 * Copyright (c) 2016 Regents of the University of Minnesota.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.umn.biomedicus.uima.adapter;

import com.google.inject.Injector;
import edu.umn.biomedicus.framework.*;
import edu.umn.biomedicus.exc.BiomedicusException;
import org.apache.uima.resource.Resource_ImplBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guice injector resource implementation.
 *
 * @author Ben Knoll
 * @since 1.4
 */
public final class GuiceInjector extends Resource_ImplBase {
    private static final Logger LOGGER = LoggerFactory.getLogger(GuiceInjector.class);

    private final Injector injector;

    public GuiceInjector() {
        LOGGER.info("Initializing Guice Injector Resource");
        try {
            Application application = UimaBootstrapper.create();
            injector = application.getInjector();
        } catch (BiomedicusException e) {
            throw new IllegalStateException(e);
        }
    }

    public Injector getInjector() {
        return injector;
    }

    public DocumentProcessorRunner createDocumentProcessorRunner() {
        return DocumentProcessorRunner.create(injector);
    }
}
