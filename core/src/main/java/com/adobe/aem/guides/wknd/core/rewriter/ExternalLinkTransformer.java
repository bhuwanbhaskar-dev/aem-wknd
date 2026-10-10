package com.adobe.aem.guides.wknd.core.rewriter;

import org.apache.sling.rewriter.DefaultTransformer;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

public class ExternalLinkTransformer extends DefaultTransformer {

    private static final String TAG_ANCHOR = "a";
    private static final String ATTR_HREF = "href";
    private static final String ATTR_TARGET = "target";
    private static final String TARGET_BLANK = "_blank";
    private static final String ATTR_REL = "rel";
    private static final String REL_NOOPENER = "noopener noreferrer";

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        // Intercept <a> tags
        if (TAG_ANCHOR.equalsIgnoreCase(localName)) {
            String href = attributes.getValue(ATTR_HREF);
            
            // Check if link is external (starts with http or https)
            if (href != null && (href.startsWith("http://") || href.startsWith("https://"))) {
                
                // Copy existing attributes to modify them
                AttributesImpl modifiedAttributes = new AttributesImpl(attributes);
                
                // Add target="_blank" if it does not already exist
                if (modifiedAttributes.getIndex(ATTR_TARGET) == -1) {
                    modifiedAttributes.addAttribute("", ATTR_TARGET, ATTR_TARGET, "CDATA", TARGET_BLANK);
                }
                
                // Add rel="noopener noreferrer" for security if it doesn't exist
                if (modifiedAttributes.getIndex(ATTR_REL) == -1) {
                    modifiedAttributes.addAttribute("", ATTR_REL, ATTR_REL, "CDATA", REL_NOOPENER);
                }
                
                // Pass modified attributes down the pipeline
                super.startElement(uri, localName, qName, modifiedAttributes);
                return;
            }
        }
        
        // Pass unmodified elements down the pipeline
        super.startElement(uri, localName, qName, attributes);
    }
}
