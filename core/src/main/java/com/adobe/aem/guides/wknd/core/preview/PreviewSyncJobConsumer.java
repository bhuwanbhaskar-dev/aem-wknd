package com.adobe.aem.guides.wknd.core.preview;

import java.util.Calendar;
import java.util.Collections;

import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.replication.AgentIdFilter;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.ReplicationStatus;
import com.day.cq.replication.Replicator;

/**
 * Executes a bounded, Preview-only replication using the least-privileged service user.
 *
 * <p>Loop prevention Layer 2: before executing an ACTIVATE, this consumer compares
 * the Publish and Preview {@link ReplicationStatus} timestamps. If Preview is already
 * at or ahead of Publish, the job is silently completed without re-replicating.</p>
 *
 * <p>The component requires an OSGi configuration PID to activate. An empty
 * {@code .cfg.json} placed only in {@code config.author} ensures the consumer
 * stays dormant on Publish and Preview tiers.</p>
 */
@Component(
        service = JobConsumer.class,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        property = JobConsumer.PROPERTY_TOPICS + "=" + PreviewSyncConstants.JOB_TOPIC
)
public final class PreviewSyncJobConsumer implements JobConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(PreviewSyncJobConsumer.class);

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    private Replicator replicator;

    @Override
    public JobResult process(Job job) {
        String path = job.getProperty(PreviewSyncConstants.JOB_PATH, String.class);
        String actionName = job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class);
        if (path == null || actionName == null) {
            LOGGER.error("Invalid Preview synchronization job {}", job.getId());
            return JobResult.CANCEL;
        }

        ReplicationActionType actionType;
        try {
            actionType = ReplicationActionType.valueOf(actionName);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Unsupported replication action {} for {}", actionName, path);
            return JobResult.CANCEL;
        }

        try (ResourceResolver resolver = resourceResolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, PreviewSyncConstants.SERVICE_NAME))) {

            // Loop prevention Layer 2: for ACTIVATE, compare Publish vs Preview timestamps
            if (actionType == ReplicationActionType.ACTIVATE && isPreviewAlreadyCurrent(resolver, path)) {
                LOGGER.debug("Preview status for {} is already up-to-date; skipping sync", path);
                return JobResult.OK;
            }

            Session session = resolver.adaptTo(Session.class);
            if (session == null) {
                LOGGER.error("The preview-sync resolver did not adapt to a JCR session");
                return JobResult.FAILED;
            }

            ReplicationOptions options = new ReplicationOptions();
            options.setFilter(new AgentIdFilter(PreviewSyncConstants.PREVIEW_AGENT_ID));

            replicator.replicate(session, actionType, path, options);
            LOGGER.info("Queued {} for Preview replication ({})", path, actionType);
            return JobResult.OK;
        } catch (ReplicationException | org.apache.sling.api.resource.LoginException e) {
            LOGGER.warn("Preview replication failed for {}; the Sling Job will retry", path, e);
            return JobResult.FAILED;
        }
    }

    /**
     * Checks whether the Preview tier's replication timestamp is already at or ahead
     * of the Publish tier's timestamp for the given path. If so, re-replicating would
     * be redundant (and could cause an infinite loop).
     *
     * @return {@code true} if Preview is already current and replication should be skipped
     */
    private boolean isPreviewAlreadyCurrent(ResourceResolver resolver, String path) {
        Resource resource = resolver.getResource(path);
        if (resource == null) {
            return false;
        }

        ReplicationStatus status = resource.adaptTo(ReplicationStatus.class);
        if (status == null) {
            return false;
        }

        Calendar publishTime = status.getLastPublished();
        ReplicationStatus previewStatus = status.getStatusForAgent(PreviewSyncConstants.PREVIEW_AGENT_ID);
        Calendar previewTime = previewStatus != null ? previewStatus.getLastPublished() : null;

        return previewTime != null && publishTime != null && !previewTime.before(publishTime);
    }
}
