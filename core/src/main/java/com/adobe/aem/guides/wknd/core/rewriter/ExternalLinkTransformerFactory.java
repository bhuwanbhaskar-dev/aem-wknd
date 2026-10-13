package com.adobe.aem.guides.wknd.core.rewriter;

import org.apache.sling.rewriter.Transformer;
import org.apache.sling.rewriter.TransformerFactory;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(
    service = TransformerFactory.class,
    property = {
        "pipeline.type=external-link-transformer"
    }
)
public class ExternalLinkTransformerFactory implements TransformerFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalLinkTransformerFactory.class);

    @Override
    public Transformer createTransformer() {
        LOGGER.info("Creating new ExternalLinkTransformer instance for Sling Rewriter pipeline");
        return new ExternalLinkTransformer();
    }
}
