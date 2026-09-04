package com.adobe.aem.guides.wknd.core.preview;

import java.util.Collections;

import javax.jcr.Session;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.replication.AgentFilter;
import com.day.cq.replication.Agent;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.Replicator;

/** Executes a bounded, Preview-only replication using the least-privileged service user. */
@Component(
        service = JobConsumer.class,
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
            Session session = resolver.adaptTo(Session.class);
            if (session == null) {
                LOGGER.error("The preview-sync resolver did not adapt to a JCR session");
                return JobResult.FAILED;
            }

            ReplicationOptions options = new ReplicationOptions();
            options.setFilter(new AgentFilter() {
                @Override
                public boolean isIncluded(Agent agent) {
                    return PreviewSyncConstants.PREVIEW_AGENT_ID.equals(agent.getId());
                }
            });
            replicator.replicate(session, actionType, path, options);
            LOGGER.info("Queued {} for Preview replication", path);
            return JobResult.OK;
        } catch (ReplicationException | org.apache.sling.api.resource.LoginException e) {
            LOGGER.warn("Preview replication failed for {}; the Sling Job will retry", path, e);
            return JobResult.FAILED;
        }
    }
}
