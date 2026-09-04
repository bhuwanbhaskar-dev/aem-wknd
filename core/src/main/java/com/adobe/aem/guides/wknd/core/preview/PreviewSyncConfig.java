package com.adobe.aem.guides.wknd.core.preview;

import org.apache.sling.caconfig.annotation.Configuration;
import org.apache.sling.caconfig.annotation.Property;

/**
 * Site-level opt-in for synchronizing content published to Publish to Preview.
 * The default is deliberately disabled because Preview is shared by stakeholders.
 */
@Configuration(
        name = PreviewSyncConfig.NAME,
        label = "WKND Preview Synchronization",
        description = "Controls Publish-to-Preview synchronization for a site or content root"
)
public @interface PreviewSyncConfig {

    String NAME = "wknd.previewSyncConfig";

    @Property(
            label = "Enable automatic Preview synchronization",
            description = "Opt in this content root and its descendants to Publish-to-Preview synchronization"
    )
    boolean enableAutoSync() default false;
}
