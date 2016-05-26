/*
 * Copyright 2000-2016 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.humminbird.tutorial.template;

import com.vaadin.annotations.EventHandler;
import com.vaadin.humminbird.tutorial.annotations.CodeFor;
import com.vaadin.hummingbird.nodefeature.ModelMap;
import com.vaadin.ui.Template;

@CodeFor("tutorial-template-event-handlers.asciidoc")
public class EventHandlers {
    public class MyTemplate extends Template {
        @EventHandler
        private void sayHello(String name) {
            getElement().getNode().getFeature(ModelMap.class)
                    .setValue("helloText", "Hello " + name);
        }
    }
}