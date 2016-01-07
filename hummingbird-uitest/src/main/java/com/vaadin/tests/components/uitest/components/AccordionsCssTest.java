package com.vaadin.tests.components.uitest.components;

import com.vaadin.server.ExternalResource;
import com.vaadin.server.UserError;
import com.vaadin.tests.components.uitest.TestSampler;
import com.vaadin.ui.Accordion;
import com.vaadin.ui.Label;
import com.vaadin.ui.themes.ValoTheme;

public class AccordionsCssTest {

    private TestSampler parent;
    private int debugIdCounter = 0;

    public AccordionsCssTest(TestSampler parent) {
        this.parent = parent;

        Accordion def = createAccordionWith("Def Accordion", null);
        parent.addComponent(def);

        Accordion light = createAccordionWith("Borderless Accordion",
                ValoTheme.ACCORDION_BORDERLESS);
        parent.addComponent(light);

    }

    private Accordion createAccordionWith(String caption, String styleName) {
        Accordion acc = new Accordion();
        acc.setId("accordion" + debugIdCounter++);
        acc.setCaption(caption);
        acc.setComponentError(new UserError("A error message..."));

        if (styleName != null) {
            acc.addStyleName(styleName);
        }

        Label l1 = new Label("There are no previously saved actions.");
        Label l2 = new Label("There are no saved notes.");
        Label l3 = new Label("There are currently no issues.");

        acc.addTab(l1, "Actions", new ExternalResource(parent.ICON_URL));
        acc.addTab(l2, "Notes", new ExternalResource(parent.ICON_URL));
        acc.addTab(l3, "Issues", new ExternalResource(parent.ICON_URL));

        acc.getTab(l2).setEnabled(false);

        return acc;
    }

}