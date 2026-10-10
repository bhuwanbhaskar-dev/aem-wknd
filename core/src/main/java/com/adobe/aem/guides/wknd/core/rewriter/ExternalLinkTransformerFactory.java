package com.adobe.aem.guides.wknd.core.rewriter;

import org.apache.sling.rewriter.Transformer;
import org.apache.sling.rewriter.TransformerFactory;
import org.osgi.service.component.annotations.Component;

@Component(
    service = TransformerFactory.class,
    property = {
        "pipeline.type=external-link-transformer"
    }
)
public class ExternalLinkTransformerFactory implements TransformerFactory {
    
    @Override
    public Transformer createTransformer() {
        return new ExternalLinkTransformer();
    }
}
