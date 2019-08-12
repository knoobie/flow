export interface FlowConfig {
    imports ?: () => void;
}

interface AppConfig {
    productionMode: boolean,
    appId: string,
    uidl: object
}

interface AppInitResponse {
    appConfig: AppConfig;
}

export interface NavigationParameters {
    path: string;
}

/**
 * Client API for flow UI operations.
 */
export class Flow {
    config ?: FlowConfig;
    response ?: AppInitResponse;

    constructor(config?: FlowConfig) {
        if (config) {
            this.config = config;
        }
    }

    /**
     * Load flow client module and initialize UI in server side.
     */
    async start(): Promise<AppInitResponse> {
        // Do not start flow twice
        if (!this.response) {
            // Initialize server side UI
            this.response = await this.initFlowUi();

            // Load bootstrap script with server side parameters
            const bootstrapMod = await import('./FlowBootstrap');
            await bootstrapMod.init(this.response);

            // Load flow-client module
            const clientMod = await import('./FlowClient');
            await this.initFlowClient(clientMod);

            // // Load custom modules defined by user
            if (this.config && this.config.imports) {
                await this.config.imports();
            }
        }
        return this.response;
    }

    /**
     * Go to a route defined in server.
     */
    async navigate(params : NavigationParameters): Promise<HTMLElement> {
        await this.start();
        return this.getFlowElement(params.path);
    }

    private async getFlowElement(routePath : string): Promise<HTMLElement> {
        return new Promise(resolve => {
            // we create any tag using the route as reference
            const tag = 'flow-' + routePath.replace(/[^\w]+/g, "-").toLowerCase();

            // flow use body for keep references
            const flowRoot = document.body as any;
            flowRoot.$ = flowRoot.$ || {counter: 0};

            // Create the wrapper element with an unique id
            const id  = `${tag}-${flowRoot.$.counter ++}`;
            const element = flowRoot.$[id] = document.createElement(tag);
            element.id = id;
            window.console.log("Created new element for a flow view: " + id);

            // The callback run from server side once the view is ready
            (element as any).serverConnected = () => resolve(element);

            // Call server side to navigate to the given route
            flowRoot.$server.connectClient(tag, id, routePath);
        });
    }

    private async initFlowClient(clientMod: any): Promise<void> {
        clientMod.init();
        // client init is async, we need to loop until initialized
        return new Promise(resolve => {
            const $wnd = window as any;
            const intervalId = setInterval(() => {
                // client `isActive() == true` while initializing
                const initializing = Object.keys($wnd.Vaadin.Flow.clients)
                  .reduce((prev, id) => prev || $wnd.Vaadin.Flow.clients[id].isActive(), false);
                if (!initializing) {
                    clearInterval(intervalId);
                    resolve();
                }
            }, 5);
        });
    }

    private async initFlowUi(): Promise<AppInitResponse> {
        return new Promise((resolve, reject) => {
            const httpRequest = new (window as any).XMLHttpRequest();
            httpRequest.open('GET', 'VAADIN/?v-r=init');
            httpRequest.onload = () => {
                if (httpRequest.getResponseHeader('content-type') === 'application/json') {
                    resolve(JSON.parse(httpRequest.responseText));
                } else {
                    reject(new Error(
                        `Invalid server response when initializing Flow UI.
                        ${httpRequest.status}
                        ${httpRequest.responseText}`));
                }
            };
            httpRequest.send();
        });
    }
}
