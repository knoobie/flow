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
package com.vaadin.ui;

import org.junit.Assert;
import org.junit.Test;

import com.vaadin.server.VaadinRequest;
import com.vaadin.shared.ui.ui.Transport;

/**
 * @author Vaadin Ltd
 */
public class PushConfigurationTransportTest {
    @Test
    public void testTransportModes() throws Exception {
        UI ui = new UI() {

            @Override
            protected void init(VaadinRequest request) {
                // TODO Auto-generated method stub

            }

        };
        for (Transport transport : Transport.values()) {
            ui.getPushConfiguration().setTransport(transport);
            Assert.assertEquals(ui.getPushConfiguration().getTransport(),
                    transport);

            boolean alwaysXhr = ((PushConfigurationImpl) ui
                    .getPushConfiguration()).state.alwaysUseXhrForServerRequests;
            if (transport == Transport.WEBSOCKET_XHR) {
                Assert.assertTrue(alwaysXhr);
            } else {
                Assert.assertFalse(alwaysXhr);
            }
        }

    }
}