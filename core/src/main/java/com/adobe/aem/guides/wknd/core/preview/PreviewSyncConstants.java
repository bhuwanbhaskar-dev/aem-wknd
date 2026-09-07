package com.adobe.aem.guides.wknd.core.preview;

final class PreviewSyncConstants {

    static final String SERVICE_NAME = "preview-sync";

    /**
     * Name of the Sling Content Distribution agent that targets the Preview tier.
     */
    static final String PREVIEW_AGENT_ID = "preview";

    /** Name of the SCD agent that targets the Publish tier. */
    static final String PUBLISH_AGENT_ID = "publish";

    static final String JOB_TOPIC = "com/adobe/aem/guides/wknd/preview-sync";
    static final String JOB_PATH = "path";
    static final String JOB_ACTION = "action";

    /**
     * Maximum number of ancestor levels to walk when resolving CAConfig for a
     * deleted
     * (already-removed) resource during a DEACTIVATE event.
     */
    static final int MAX_ANCESTOR_DEPTH = 10;

    private PreviewSyncConstants() {
    }
}
