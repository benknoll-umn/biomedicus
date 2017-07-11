/*
 * Copyright (c) 2017 Regents of the University of Minnesota.
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

package edu.umn.biomedicus.framework;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Named;
import edu.umn.biomedicus.exc.BiomedicusException;
import edu.umn.biomedicus.framework.store.Document;
import java.util.Map;
import javax.annotation.Nullable;

/**
 *
 */
public class AggregatorRunner extends ScopedWork {

  @Nullable
  private Class<? extends Aggregator> aggClass;

  @Nullable
  private Aggregator aggregator;

  @Inject
  AggregatorRunner(Injector injector,
      @Named("globalSettings") Map<String, Object> globalSettings,
      SettingsTransformer settingsTransformer) {
    super(injector, globalSettings, settingsTransformer);
  }

  public static AggregatorRunner create(Injector injector) {
    return injector.getInstance(AggregatorRunner.class);
  }

  public void setAggregatorClass(Class<? extends Aggregator> aggClass) {
    this.aggClass = aggClass;
  }

  public void setAggregatorClassName(String name) throws ClassNotFoundException {
    this.aggClass = Class.forName(name).asSubclass(Aggregator.class);
  }

  @Override
  public void initialize(@Nullable Map<String, Object> processorSettings,
      @Nullable Map<Key<?>, Object> processorScopedObjects) throws BiomedicusException {
    super.initialize(processorSettings, processorScopedObjects);

    checkNotNull(processorContext);
    checkNotNull(settingsInjector);

    try {
      processorContext.call(() -> {
        aggregator = settingsInjector.getInstance(aggClass);
        return null;
      });
    } catch (Exception e) {
      throw new BiomedicusException(e);
    }
  }

  /**
   * Runs a single document through the aggregate processor.
   *
   * @param document the document to add
   * @throws BiomedicusException any exceptions thrown by the processor
   */
  public void processDocument(Document document) throws BiomedicusException {
    checkNotNull(processorContext);
    checkNotNull(settingsInjector);
    checkNotNull(aggregator);

    try {
      processorContext.call(() -> {
        aggregator.addDocument(document);
        return null;
      });
    } catch (Exception e) {
      throw new BiomedicusException(e);
    }
  }

  /**
   * Calls the done method on the processor
   *
   * @throws BiomedicusException any exception thrown by the aggregator.
   */
  public void doneProcessing() throws BiomedicusException {
    checkNotNull(processorContext);
    checkNotNull(aggregator);
    try {
      processorContext.call(() -> {
        aggregator.done();
        return null;
      });
    } catch (Exception e) {
      throw new BiomedicusException(e);
    }
  }
}