package com.adobe.aem.guides.wknd.core.mcp;

import com.adobe.acs.commons.mcp.ProcessDefinitionFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import com.day.cq.wcm.api.reference.ReferenceProvider;
import org.apache.sling.api.resource.ResourceResolverFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component(service = ProcessDefinitionFactory.class)
public class CrossBoundaryReporterFactory extends ProcessDefinitionFactory<CrossBoundaryReporter> {

    @Reference(
            cardinality = ReferenceCardinality.MULTIPLE,
            policy = ReferencePolicy.DYNAMIC
    )
    private volatile List<ReferenceProvider> referenceProviders = new CopyOnWriteArrayList<>();

    @Override
    public String getName() {
        return "Cross-Boundary Reference Reporter";
    }

    @Override
    protected CrossBoundaryReporter createProcessDefinitionInstance() {
        return new CrossBoundaryReporter(referenceProviders);
    }

    protected void bindReferenceProviders(ReferenceProvider referenceProvider) {
        referenceProviders.add(referenceProvider);
    }

    protected void unbindReferenceProviders(ReferenceProvider referenceProvider) {
        referenceProviders.remove(referenceProvider);
    }
}
