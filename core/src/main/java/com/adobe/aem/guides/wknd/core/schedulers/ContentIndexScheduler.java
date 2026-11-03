/*
 * Copyright 2024 Adobe Systems Incorporated
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
package com.adobe.aem.guides.wknd.core.schedulers;

import com.adobe.aem.guides.wknd.core.services.ContentIndexService;
import com.adobe.aem.guides.wknd.core.services.ContentIndexService.ContentIndexException;
import com.adobe.aem.guides.wknd.core.services.ContentIndexService.IndexEntry;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Sling Scheduler that periodically rebuilds the WKND content index.
 *
 * <h2>Key learning: separation of concerns with service users</h2>
 * <p>
 * Notice that this scheduler knows NOTHING about:
 * <ul>
 *   <li>ResourceResolverFactory</li>
 *   <li>The subservice name "content-indexer"</li>
 *   <li>The system user "wknd-content-indexer-service"</li>
 *   <li>JCR paths</li>
 * </ul>
 * All of that complexity is encapsulated inside {@link ContentIndexService}.
 * The scheduler simply calls {@code buildIndex()} and logs the result.
 * This is the correct architectural separation — callers don't manage
 * authentication; the service does.
 * </p>
 *
 * <h2>How Sling Scheduler works</h2>
 * <p>
 * When you register a {@code Runnable} as an OSGi service with the property
 * {@code scheduler.expression} set to a cron expression, the Sling Scheduler
 * service automatically invokes {@code run()} on that schedule.
 *
 * The cron format is: {@code seconds minutes hours day-of-month month day-of-week}
 * </p>
 *
 * <h2>Why scheduler.concurrent=false matters</h2>
 * <p>
 * Without this, if a run takes longer than the interval, a second run starts
 * before the first completes — two concurrent writes to the same index node.
 * {@code scheduler.concurrent=false} ensures only one run executes at a time.
 * </p>
 */
@Component(
    service = Runnable.class,
    property = {
        "scheduler.name=wknd-content-index-scheduler",
        "scheduler.concurrent:Boolean=false"
    }
)
@Designate(ocd = ContentIndexScheduler.Configuration.class)
public class ContentIndexScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(ContentIndexScheduler.class);

    @ObjectClassDefinition(
        name        = "WKND Content Index Scheduler",
        description = "Periodically rebuilds the WKND content index at /var/wknd/content-index"
    )
    public @interface Configuration {

        @AttributeDefinition(
            name        = "Cron Expression",
            description = "Sling cron expression. Default: every 5 minutes."
        )
        String scheduler_expression() default "0 0/5 * * * ?";

        @AttributeDefinition(
            name        = "Enabled",
            description = "Set to false to pause automatic indexing without undeploying."
        )
        boolean enabled() default true;
    }

    @Reference
    private ContentIndexService contentIndexService;

    private volatile boolean enabled;

    @Activate
    @Modified
    protected void activate(Configuration config) {
        this.enabled = config.enabled();
        LOG.info("[ContentIndexScheduler] {} (cron='{}')",
            enabled ? "Activated" : "Activated but disabled via config",
            config.scheduler_expression());
    }

    @Deactivate
    protected void deactivate() {
        LOG.info("[ContentIndexScheduler] Deactivated — automatic indexing stopped");
    }

    /**
     * Called by the Sling Scheduler on every cron tick.
     *
     * <p>This method has zero JCR knowledge. All repository access,
     * authentication, and session management is handled inside
     * {@link ContentIndexService#buildIndex()}.</p>
     *
     * <p>Exceptions are caught and logged — never rethrown from a scheduler
     * {@code run()} method, as an uncaught exception can suppress future
     * scheduled executions.</p>
     */
    @Override
    public void run() {
        if (!enabled) {
            LOG.debug("[ContentIndexScheduler] Skipping — disabled via OSGi config");
            return;
        }

        LOG.info("[ContentIndexScheduler] Scheduled index rebuild starting");
        long start = System.currentTimeMillis();

        try {
            List<IndexEntry> entries = contentIndexService.buildIndex();
            LOG.info("[ContentIndexScheduler] Rebuilt successfully: {} pages indexed in {}ms",
                entries.size(), System.currentTimeMillis() - start);
        } catch (ContentIndexException e) {
            LOG.error("[ContentIndexScheduler] Rebuild failed after {}ms",
                System.currentTimeMillis() - start, e);
            // Do NOT rethrow — swallow to keep the scheduler alive for the next tick
        }
    }
}
