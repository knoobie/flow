package com.vaadin.hummingbird.kernel;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.vaadin.annotations.TemplateEventHandler;
import com.vaadin.hummingbird.kernel.TemplateModelTest.SubModelType;
import com.vaadin.server.VaadinRequest;
import com.vaadin.ui.Template;
import com.vaadin.ui.UI;

import elemental.json.Json;
import elemental.json.JsonArray;
import elemental.json.JsonValue;

public class TemplateComponentTest {
    private static class TestTemplateComponent extends Template {
        public List<Object> receivedValues = new ArrayList<>();

        public TestTemplateComponent() {
            super(BasicElementTemplate.get());
        }

        public void onBrowserEvent(String methodName, JsonValue... params) {
            JsonArray paramsJson = Json.createArray();
            for (int i = 0; i < params.length; i++) {
                paramsJson.set(i, params[i]);
            }

            int promiseId = 0;

            super.onBrowserEvent(getNode(), methodName, paramsJson, promiseId);
        }
    }

    @Test
    public void testSimpleHandlerMapping() {
        TestTemplateComponent template = new TestTemplateComponent() {
            @TemplateEventHandler
            private void withString(String value) {
                receivedValues.add(value);
            }
        };

        template.onBrowserEvent("withString", Json.create("My string"));

        Assert.assertEquals(Arrays.asList("My string"),
                template.receivedValues);
    }

    @Test
    public void testHandlerWithElementParameter() {
        TestTemplateComponent template = new TestTemplateComponent() {
            @TemplateEventHandler
            private void withElement(Element element) {
                receivedValues.add(element);
            }
        };

        UI ui = new UI() {
            @Override
            protected void init(VaadinRequest request) {
                // Nothing here, never run
            }
        };
        ui.setContent(template);
        ui.registerTemplate(template.getElement().getTemplate());

        JsonArray elementDescriptor = Json.createArray();
        elementDescriptor.set(0, template.getElement().getNode().getId());
        elementDescriptor.set(1, template.getElement().getTemplate().getId());

        template.onBrowserEvent("withElement", elementDescriptor);

        Assert.assertEquals(Arrays.asList(template.getElement()),
                template.receivedValues);
    }

    @Test
    public void testHandlerWithNodeParameter() {
        TestTemplateComponent template = new TestTemplateComponent() {
            @TemplateEventHandler
            private void withNode(SubModelType node) {
                receivedValues.add(node);
            }
        };

        UI ui = new UI() {
            @Override
            protected void init(VaadinRequest request) {
                // Nothing here, never run
            }
        };
        ui.setContent(template);
        ui.registerTemplate(template.getElement().getTemplate());

        StateNode node = StateNode.create();
        ui.getRootNode().put("node", node);

        template.onBrowserEvent("withNode", Json.create(node.getId()));

        Assert.assertEquals(
                Arrays.asList(Template.Model.wrap(node, SubModelType.class)),
                template.receivedValues);
    }

    @Test(expected = RuntimeException.class)
    public void testMissingEventHandlerAnnotation() {
        TestTemplateComponent template = new TestTemplateComponent() {
            private void withString(String value) {
                receivedValues.add(value);
            }
        };

        template.onBrowserEvent("withString", Json.create("My string"));
    }

    @Test(expected = RuntimeException.class)
    public void testMissingEventHandlerMethod() {
        TestTemplateComponent template = new TestTemplateComponent() {
            // No method here
        };

        template.onBrowserEvent("withString", Json.create("My string"));
    }

}