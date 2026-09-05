package com.adobe.aem.guides.wknd.core.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Calendar;
import java.util.Collections;

import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.ReplicationStatus;
import com.day.cq.replication.Replicator;

@ExtendWith(MockitoExtension.class)
class PreviewSyncJobConsumerTest {

    @InjectMocks
    private PreviewSyncJobConsumer consumer;

    @Mock
    private ResourceResolverFactory resourceResolverFactory;

    @Mock
    private Replicator replicator;

    @Mock
    private ResourceResolver resolver;

    @Mock
    private Session session;

    @Mock
    private Resource resource;

    @Mock
    private Job job;

    @BeforeEach
    void setUp() throws LoginException {
        lenient().when(resourceResolverFactory.getServiceResourceResolver(any()))
                .thenReturn(resolver);
        lenient().when(resolver.adaptTo(Session.class)).thenReturn(session);
    }

    @Test
    @DisplayName("Should CANCEL when job path is null")
    void shouldCancelWhenPathIsNull() {
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(null);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("ACTIVATE");

        assertEquals(JobConsumer.JobResult.CANCEL, consumer.process(job));
    }

    @Test
    @DisplayName("Should CANCEL when action is invalid")
    void shouldCancelWhenActionIsInvalid() {
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn("/content/wknd/us/en");
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("INVALID_TYPE");

        assertEquals(JobConsumer.JobResult.CANCEL, consumer.process(job));
    }

    @Test
    @DisplayName("Should skip ACTIVATE when Preview is already current (circuit breaker)")
    void shouldSkipWhenPreviewAlreadyCurrent() {
        String path = "/content/wknd/us/en";
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(path);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("ACTIVATE");
        when(resolver.getResource(path)).thenReturn(resource);

        ReplicationStatus publishStatus = mock(ReplicationStatus.class);
        ReplicationStatus previewStatus = mock(ReplicationStatus.class);
        Calendar publishTime = Calendar.getInstance();
        publishTime.setTimeInMillis(1000L);
        Calendar previewTime = Calendar.getInstance();
        previewTime.setTimeInMillis(2000L); // Preview is ahead

        when(resource.adaptTo(ReplicationStatus.class)).thenReturn(publishStatus);
        when(publishStatus.getLastPublished()).thenReturn(publishTime);
        when(publishStatus.getStatusForAgent(PreviewSyncConstants.PREVIEW_AGENT_ID)).thenReturn(previewStatus);
        when(previewStatus.getLastPublished()).thenReturn(previewTime);

        JobConsumer.JobResult result = consumer.process(job);

        assertEquals(JobConsumer.JobResult.OK, result);
        verifyNoInteractions(replicator);
    }

    @Test
    @DisplayName("Should replicate when Preview is behind Publish")
    void shouldReplicateWhenPreviewIsBehind() throws Exception {
        String path = "/content/wknd/us/en";
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(path);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("ACTIVATE");
        when(resolver.getResource(path)).thenReturn(resource);

        ReplicationStatus publishStatus = mock(ReplicationStatus.class);
        ReplicationStatus previewStatus = mock(ReplicationStatus.class);
        Calendar publishTime = Calendar.getInstance();
        publishTime.setTimeInMillis(2000L);
        Calendar previewTime = Calendar.getInstance();
        previewTime.setTimeInMillis(1000L); // Preview is behind

        when(resource.adaptTo(ReplicationStatus.class)).thenReturn(publishStatus);
        when(publishStatus.getLastPublished()).thenReturn(publishTime);
        when(publishStatus.getStatusForAgent(PreviewSyncConstants.PREVIEW_AGENT_ID)).thenReturn(previewStatus);
        when(previewStatus.getLastPublished()).thenReturn(previewTime);

        JobConsumer.JobResult result = consumer.process(job);

        assertEquals(JobConsumer.JobResult.OK, result);
        verify(replicator).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), eq(path), any(ReplicationOptions.class));
    }

    @Test
    @DisplayName("Should replicate when Preview has never been published (null status)")
    void shouldReplicateWhenPreviewStatusIsNull() throws Exception {
        String path = "/content/wknd/us/en";
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(path);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("ACTIVATE");
        when(resolver.getResource(path)).thenReturn(resource);

        ReplicationStatus publishStatus = mock(ReplicationStatus.class);
        when(resource.adaptTo(ReplicationStatus.class)).thenReturn(publishStatus);
        when(publishStatus.getLastPublished()).thenReturn(Calendar.getInstance());
        when(publishStatus.getStatusForAgent(PreviewSyncConstants.PREVIEW_AGENT_ID)).thenReturn(null);

        JobConsumer.JobResult result = consumer.process(job);

        assertEquals(JobConsumer.JobResult.OK, result);
        verify(replicator).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), eq(path), any(ReplicationOptions.class));
    }

    @Test
    @DisplayName("Should DEACTIVATE without circuit breaker check")
    void shouldDeactivateWithoutCircuitBreaker() throws Exception {
        String path = "/content/wknd/us/en";
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(path);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("DEACTIVATE");

        JobConsumer.JobResult result = consumer.process(job);

        assertEquals(JobConsumer.JobResult.OK, result);
        verify(replicator).replicate(eq(session), eq(ReplicationActionType.DEACTIVATE), eq(path), any(ReplicationOptions.class));
    }

    @Test
    @DisplayName("Should return FAILED on ReplicationException (allows Sling Job retry)")
    void shouldReturnFailedOnReplicationException() throws Exception {
        String path = "/content/wknd/us/en";
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(path);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("ACTIVATE");
        when(resolver.getResource(path)).thenReturn(null); // No resource → skips circuit breaker

        doThrow(new ReplicationException("test")).when(replicator)
                .replicate(any(Session.class), any(ReplicationActionType.class), any(String.class), any(ReplicationOptions.class));

        assertEquals(JobConsumer.JobResult.FAILED, consumer.process(job));
    }

    @Test
    @DisplayName("Should return FAILED when session is null")
    void shouldReturnFailedWhenSessionIsNull() {
        String path = "/content/wknd/us/en";
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(path);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("ACTIVATE");
        when(resolver.getResource(path)).thenReturn(null);
        when(resolver.adaptTo(Session.class)).thenReturn(null);

        assertEquals(JobConsumer.JobResult.FAILED, consumer.process(job));
    }
}
