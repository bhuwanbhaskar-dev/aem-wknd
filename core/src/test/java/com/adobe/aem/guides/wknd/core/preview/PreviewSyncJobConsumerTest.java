package com.adobe.aem.guides.wknd.core.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Calendar;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.distribution.DistributionRequest;
import org.apache.sling.distribution.DistributionRequestType;
import org.apache.sling.distribution.DistributionResponse;
import org.apache.sling.distribution.Distributor;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.day.cq.replication.ReplicationStatus;

@ExtendWith(MockitoExtension.class)
class PreviewSyncJobConsumerTest {

    @InjectMocks
    private PreviewSyncJobConsumer consumer;

    @Mock
    private ResourceResolverFactory resourceResolverFactory;

    @Mock
    private Distributor distributor;

    @Mock
    private ResourceResolver resolver;

    @Mock
    private Resource resource;

    @Mock
    private Job job;

    @Mock
    private DistributionResponse distributionResponse;

    @BeforeEach
    void setUp() throws LoginException {
        lenient().when(resourceResolverFactory.getServiceResourceResolver(any()))
                .thenReturn(resolver);
        lenient().when(distributor.distribute(
                eq(PreviewSyncConstants.PREVIEW_AGENT_ID),
                eq(resolver),
                any(DistributionRequest.class)))
                .thenReturn(distributionResponse);
        lenient().when(distributionResponse.isSuccessful()).thenReturn(true);
    }

    @Test
    @DisplayName("Should CANCEL when job path is null")
    void shouldCancelWhenPathIsNull() {
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(null);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("ADD");

        assertEquals(JobConsumer.JobResult.CANCEL, consumer.process(job));
    }

    @Test
    @DisplayName("Should CANCEL when distribution type is invalid")
    void shouldCancelWhenActionIsInvalid() {
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn("/content/wknd/us/en");
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("INVALID_TYPE");

        assertEquals(JobConsumer.JobResult.CANCEL, consumer.process(job));
    }

    @Test
    @DisplayName("Should skip ADD when Preview is already current (circuit breaker)")
    void shouldSkipWhenPreviewAlreadyCurrent() {
        String path = "/content/wknd/us/en";
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(path);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("ADD");
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
        verifyNoInteractions(distributor);
    }

    @Test
    @DisplayName("Should distribute when Preview is behind Publish")
    void shouldDistributeWhenPreviewIsBehind() {
        String path = "/content/wknd/us/en";
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(path);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("ADD");
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
        verify(distributor).distribute(
                eq(PreviewSyncConstants.PREVIEW_AGENT_ID),
                eq(resolver),
                argThat(req -> req.getRequestType() == DistributionRequestType.ADD));
    }

    @Test
    @DisplayName("Should distribute when Preview has never been published (null status)")
    void shouldDistributeWhenPreviewStatusIsNull() {
        String path = "/content/wknd/us/en";
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(path);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("ADD");
        when(resolver.getResource(path)).thenReturn(resource);

        ReplicationStatus publishStatus = mock(ReplicationStatus.class);
        when(resource.adaptTo(ReplicationStatus.class)).thenReturn(publishStatus);
        when(publishStatus.getLastPublished()).thenReturn(Calendar.getInstance());
        when(publishStatus.getStatusForAgent(PreviewSyncConstants.PREVIEW_AGENT_ID)).thenReturn(null);

        JobConsumer.JobResult result = consumer.process(job);

        assertEquals(JobConsumer.JobResult.OK, result);
        verify(distributor).distribute(
                eq(PreviewSyncConstants.PREVIEW_AGENT_ID),
                eq(resolver),
                argThat(req -> req.getRequestType() == DistributionRequestType.ADD));
    }

    @Test
    @DisplayName("Should DELETE without circuit breaker check")
    void shouldDeleteWithoutCircuitBreaker() {
        String path = "/content/wknd/us/en";
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(path);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("DELETE");

        JobConsumer.JobResult result = consumer.process(job);

        assertEquals(JobConsumer.JobResult.OK, result);
        verify(distributor).distribute(
                eq(PreviewSyncConstants.PREVIEW_AGENT_ID),
                eq(resolver),
                argThat(req -> req.getRequestType() == DistributionRequestType.DELETE));
    }

    @Test
    @DisplayName("Should return FAILED on distribution exception (allows Sling Job retry)")
    void shouldReturnFailedOnDistributionException() {
        String path = "/content/wknd/us/en";
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(path);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("ADD");
        when(resolver.getResource(path)).thenReturn(null); // skip circuit breaker

        doThrow(new RuntimeException("Distribution failed")).when(distributor)
                .distribute(any(), any(), any());

        assertEquals(JobConsumer.JobResult.FAILED, consumer.process(job));
    }

    @Test
    @DisplayName("Should return FAILED when distribution is not accepted")
    void shouldReturnFailedWhenDistributionIsNotAccepted() {
        String path = "/content/wknd/us/en";
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn(path);
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("ADD");
        when(resolver.getResource(path)).thenReturn(null);
        when(distributionResponse.isSuccessful()).thenReturn(false);
        when(distributionResponse.getMessage()).thenReturn("distribution rejected");

        assertEquals(JobConsumer.JobResult.FAILED, consumer.process(job));
    }

    @Test
    @DisplayName("Should return FAILED on LoginException")
    void shouldReturnFailedOnLoginException() throws LoginException {
        when(job.getProperty(PreviewSyncConstants.JOB_PATH, String.class)).thenReturn("/content/wknd/us/en");
        when(job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class)).thenReturn("ADD");
        when(resourceResolverFactory.getServiceResourceResolver(any()))
                .thenThrow(new LoginException("test"));

        assertEquals(JobConsumer.JobResult.FAILED, consumer.process(job));
    }
}
