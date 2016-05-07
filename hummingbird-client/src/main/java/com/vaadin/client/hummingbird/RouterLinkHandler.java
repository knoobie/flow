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
package com.vaadin.client.hummingbird;

import java.util.Objects;

import com.vaadin.client.Console;
import com.vaadin.client.Registry;
import com.vaadin.client.URIResolver;
import com.vaadin.client.WidgetUtil;
import com.vaadin.shared.ApplicationConstants;

import elemental.client.Browser;
import elemental.dom.Element;
import elemental.events.Event;
import elemental.events.EventTarget;
import elemental.events.MouseEvent;
import elemental.html.AnchorElement;

/**
 * Handler for click events originating from application navigation link
 * elements marked with {@value ApplicationConstants#ROUTER_LINK_ATTRIBUTE}.
 * <p>
 * Events are sent to server for handling.
 *
 * @author Vaadin Ltd
 */
public class RouterLinkHandler {

    private RouterLinkHandler() {
        // Only static functionality
    }

    /**
     * Adds a click event listener for the given element for intercepting
     * application navigation related click events and sending them to server.
     *
     * @param registry
     *            the registry
     * @param element
     *            the element to listen to click events in
     */
    public static void bind(Registry registry, Element element) {
        element.addEventListener("click", event -> handleClick(registry, event),
                false);
    }

    private static void handleClick(Registry registry, Event clickEvent) {
        if (hasModifierKeys(clickEvent)
                || !registry.getUILifecycle().isRunning()) {
            return;
        }

        String href = getValidLinkHref(clickEvent);
        if (href == null) {
            return;
        }

        String baseURI = ((Element) clickEvent.getCurrentTarget())
                .getOwnerDocument().getBaseURI();

        // verify that the link is actually for this application
        if (!href.startsWith(baseURI)) {
            // ain't nobody going to see this log
            Console.warn("Should not use "
                    + ApplicationConstants.ROUTER_LINK_ATTRIBUTE
                    + " attribute for an external link.");
            return;
        }

        String location = URIResolver.getBaseRelativeUri(baseURI, href);

        if (location.contains("#")) {
            // make sure fragment event gets fired after response
            new FragmentHandler(Browser.getWindow().getLocation().getHref(),
                    href).bind(registry);

            // don't send hash to server
            location = location.split("#", 2)[0];
        }

        clickEvent.preventDefault();
        Browser.getWindow().getHistory().pushState(null, null, href);
        sendServerNavigationEvent(registry, location, null);
    }

    /**
     * Gets the link href for the given event. If the event target or the link
     * href is not a valid routerlink, or is only inside page navigation (just
     * fragment change), <code>null</code> will be returned instead.
     *
     * @param clickEvent
     *            the click event for the link
     * @return the link href or <code>null</code> there is no valid href
     */
    private static String getValidLinkHref(Event clickEvent) {
        AnchorElement anchor = getRouterLink(clickEvent);
        if (anchor == null) {
            return null;
        }
        String href = anchor.getHref();
        if (href == null || isInsidePageNavigation(anchor)) {
            return null;
        }
        return href;
    }

    /**
     * Checks whether the given anchor links within the current page.
     *
     * @param anchor
     *            the link to check
     * @return <code>true</code> if links inside current page,
     *         <code>false</code> if not
     */
    private static boolean isInsidePageNavigation(AnchorElement anchor) {
        return isInsidePageNavigation(anchor.getPathname(), anchor.getHash());
    }

    /**
     * Checks whether the given path and hash are for navigating inside the same
     * page as the current one.
     * <p>
     * If the paths are different, it is always outside the current page
     * navigation.
     * <p>
     * If the paths are the same, then it is inside the current page navigation
     * unless the hashes are the same too; then it is considered reloading the
     * current page.
     *
     * @param path
     *            the path to check against
     * @param hash
     *            the hash to check against
     * @return <code>true</code> if the given location is for navigating inside
     *         the current page, <code>false</code> if not
     */
    public static boolean isInsidePageNavigation(String path, String hash) {
        String currentPath = Browser.getWindow().getLocation().getPathname();
        String currentHash = Browser.getWindow().getLocation().getHash();
        assert currentPath != null : "window.location.path should never be null";
        assert currentHash != null : "window.location.hash should never be null";
        // if same path it is always inside page unless fragment same, then it
        // is reload
        return Objects.equals(currentPath, path)
                && !Objects.equals(currentHash, hash);
    }

    /**
     * Gets the anchor element, if a router link was found between the click
     * target and the event listener.
     *
     * @param clickEvent
     *            the click event
     * @return the target anchor if found, <code>null</code> otherwise
     */
    private static AnchorElement getRouterLink(Event clickEvent) {
        assert "click".equals(clickEvent.getType());

        Element target = (Element) clickEvent.getTarget();
        EventTarget eventListenerElement = clickEvent.getCurrentTarget();
        while (target != eventListenerElement) {
            if (isRouterLinkAnchorElement(target)) {
                return (AnchorElement) target;
            }
            target = target.getParentElement();
        }

        return null;
    }

    /**
     * Checks if the given element is {@code <a routerlink>}.
     *
     * @param target
     *            the element to check
     * @return <code>true</code> if the element is a routerlink,
     *         <code>false</code> otherwise
     */
    private static boolean isRouterLinkAnchorElement(Element target) {
        return "a".equalsIgnoreCase(target.getTagName()) && target
                .hasAttribute(ApplicationConstants.ROUTER_LINK_ATTRIBUTE);
    }

    private static boolean hasModifierKeys(Event clickEvent) {
        assert "click".equals(clickEvent.getType());

        MouseEvent event = (MouseEvent) clickEvent;
        return event.isAltKey() || event.isCtrlKey() || event.isMetaKey()
                || event.isShiftKey();
    }

    /**
     * Notifies the server about navigation to the given location.
     * <p>
     * Ensures that navigation works even if the session has expired.
     *
     * @param registry
     *            the registry
     * @param location
     *            the location to navigate to, relative to the base URI
     * @param stateObject
     *            the state object or <code>null</code> if none applicable
     */
    public static void sendServerNavigationEvent(Registry registry,
            String location, Object stateObject) {
        assert registry != null;
        assert location != null;

        // If the server tells us the session has expired, we refresh (using the
        // new location) instead.
        registry.getMessageHandler().setNextResponseSessionExpiredHandler(
                () -> WidgetUtil.refresh());
        registry.getServerConnector().sendNavigationMessage(location,
                stateObject);

    }
}