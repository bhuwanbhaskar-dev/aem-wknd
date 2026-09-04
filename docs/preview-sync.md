# Publish-to-Preview synchronization

This feature is opt-in because the AEM as a Cloud Service Preview tier is shared by stakeholders and is intended for pre-production validation.

## Enablement

Create a Context-Aware Configuration named `wknd.previewSyncConfig` below the applicable site/content root configuration (`/conf/<site>`), with:

```text
enableAutoSync = true
```

The configuration is inherited by descendants through the normal CAConfig resolution rules. Do not enable it at a shared `/conf` or `/content` root unless all consumers of that root agree that Publish-to-Preview synchronization is appropriate.

The service user in this sample is scoped to the WKND content, DAM, experience-fragment, and configuration roots. For a multi-site production deployment, use separately scoped service users where sites have different security boundaries; a CAConfig toggle alone is not an authorization boundary.

## Runtime flow

1. The Author replication event handler accepts only `ACTIVATE` and `DEACTIVATE` events identified as originating from the `publish` agent.
2. For each event path, the handler resolves `wknd.previewSyncConfig` using the dedicated service user.
3. An opted-in path is submitted to the durable Sling Job topic `com/adobe/aem/guides/wknd/preview-sync`.
4. The job consumer uses the same service user and calls the Replication API with an `AgentFilter` that selects only `preview`.

One job contains one path. This keeps jobs retryable and bounded. Large/deep trees must use Adobe's Tree Activation workflow step with `agentId=preview`; this handler is not a bulk-tree replication mechanism.

## Required deployment validation

- Confirm the Cloud Manager deployment completed successfully.
- Confirm the service-user mapping and Repoinit output in the AEM Developer Console.
- Confirm the Preview service is accessible through an appropriate Cloud Manager IP allowlist.
- Enable the CAConfig for a test content root only.
- Publish, verify the page in Preview, then deactivate and verify removal from Preview.
- Inspect AEM logs and Tools > Deployment > Distribution for the Preview queue.

The implementation uses at-least-once Sling Job execution, so the Preview operation must be treated as retryable. Adobe's Replication API documentation also notes that Preview-only status is read through the per-agent status rather than the overall activation flag.
