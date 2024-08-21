import com.hsbc.cranker.connector.CrankerConnector;
import com.hsbc.cranker.connector.RouterEventListener;
import com.hsbc.cranker.connector.RouterRegistration;
import com.hsbc.cranker.mucranker.CrankerRouter;
import com.hsbc.cranker.mucranker.CrankerRouterBuilder;
import com.hsbc.cranker.connector.CrankerConnectorBuilder;
import io.muserver.Method;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;

import java.net.URI;

public class Main {

    public static void main(String[] args) {

        URI crankerPublicWebServerUri = null;
        URI crankerDmzRegistrationURI = null;
        String webSocketRoutedTrafficHookup = null;
        {

            // Use the mucranker library to create a router object - this creates handlers
            CrankerRouter crankerRouter = CrankerRouterBuilder.crankerRouter().start();
            // this crankerRouter isn't itself listening on a socket. There are two usages below.

            // Start the server that browsers or clients-api calls will send HTTP requests to
            // This would be accessible outside your DMZ
            MuServer crankerWebServer = MuServerBuilder.muServer()
                    .withHttpsPort(8443)
                    .addHandler(crankerRouter.createHttpHandler())
                    .start();

            // Start a coupled server which will listen to connector registrations on a websocket
            // This would not be accessible to traffic outside the DMZ
            MuServer crankerRegistrationServer = MuServerBuilder.muServer()
                    .withHttpsPort(8444)
                    //.withInterface(z.b.c.d) // maybe you can set non-public traffic this way, maybe not
                    .addHandler(crankerRouter.createRegistrationHandler())
                    .start();

            crankerDmzRegistrationURI = crankerRegistrationServer.uri();

            crankerPublicWebServerUri = crankerWebServer.uri();
            System.out.println("Cranker is available in public at " + crankerPublicWebServerUri);
            System.out.println("App/Service registration for that Cranker is available (DMZ) at " + crankerRegistrationServer.uri());
            webSocketRoutedTrafficHookup = crankerDmzRegistrationURI.toString().replace("https", "wss");
        }

        {
            // This is the business app. For this demo it is using MuServer again,
            // but it could easily be SpringBoot or Http4K, Quarkus, Micronaut, Vert.x, Heildon, or Jooby.
            // For this demo, it is in the same JVM, but it could be as easily be
            //      * in a separate JVM/process,
            //      * or in another machine / VM / host
            //      * or many provisioned (explicit horizontal scaling, auto-scaled cluster, combination of those)
            MuServer helloWorldExampleApp = MuServerBuilder.httpServer()
                    .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                        response.write("Hello, world");
                    })
                    .start();
            System.out.println("HelloWorld server (internal network) at " + helloWorldExampleApp.uri() + " (try it)");

             // Each deployment of the business app/service would need to register itself with the cranker:

            CrankerConnector connector = CrankerConnectorBuilder.connector()
                    .withRouterRegistrationListener(new RouterEventListener() {
                        public void onRegistrationChanged(ChangeData data) {
                            System.out.println("Router registration changed: " + data);
                        }
                        public void onSocketConnectionError(RouterRegistration router, Throwable exception) {
//                            System.out.println("Error connecting to " + router + " - " + exception.getMessage());
                        }
                    })
                    .withRouterLookupByDNS(URI.create(webSocketRoutedTrafficHookup))
                    .withRoute("path-prefix")
                    .withTarget(helloWorldExampleApp.uri())
                    .start();

            // v

            System.out.println("Cranker routed traffic hookup " + webSocketRoutedTrafficHookup);
            System.out.println("Cranked HelloWorld is available in public at " + crankerPublicWebServerUri + "/path-prefix/ (try it)");

        }
    }
}
