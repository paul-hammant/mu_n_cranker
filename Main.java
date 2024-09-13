import com.hsbc.cranker.connector.CrankerConnector;
import com.hsbc.cranker.connector.RouterEventListener;
import com.hsbc.cranker.connector.RouterRegistration;
import com.hsbc.cranker.mucranker.CrankerRouter;
import com.hsbc.cranker.mucranker.CrankerRouterBuilder;
import com.hsbc.cranker.connector.CrankerConnectorBuilder;
import io.muserver.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    public static String HEADERCHARS = "abcdefghij".repeat(791);

    public static void main(String[] args) throws MalformedURLException {

        System.out.println("HEADER_VAL_LEN: " + HEADERCHARS.length());

        URI crankerPublicWebServerUri = null;
        URI crankerDmzRegistrationURI = null;
        String webSocketRoutedTrafficHookup = null;

        // Cranker setup
        {
            // Use the mucranker library to create a router object - this creates handlers
            CrankerRouter crankerRouter = CrankerRouterBuilder.crankerRouter().start();

            // Start the server that browsers or clients-api calls will send HTTP requests to
            // This would be accessible outside your DMZ
            MuServer crankerWebServer = MuServerBuilder.muServer()
                    .withHttpPort(8443)
                    .addHandler(crankerRouter.createHttpHandler())
                    .start();

            // Start a coupled server which will listen to connector registrations on a websocket
            // This would not be accessible to traffic outside the DMZ
            MuServer crankerRegistrationServer = MuServerBuilder.muServer()
                    .withHttpPort(8444)
                    //.withInterface(z.b.c.d) // maybe you can set non-public traffic this way, maybe not
                    .addHandler(crankerRouter.createRegistrationHandler())
                    .start();

            crankerDmzRegistrationURI = crankerRegistrationServer.uri();

            crankerPublicWebServerUri = crankerWebServer.uri();
            System.out.println("Cranker is available in public at " + crankerPublicWebServerUri);
            System.out.println("App/Service registration for that Cranker is available (DMZ) at " + crankerRegistrationServer.uri());
            webSocketRoutedTrafficHookup = crankerDmzRegistrationURI.toString()
                    .replace("https", "wss")
                    .replace("http", "ws");
        }

        // Business app or service and cranker-connector (normally on a separate JVM/box)
        {
            final String helloWorldAppPathPrefix = "abc";

            // This is the business app. For this demo it is using MuServer again,
            // but it could easily be SpringBoot or Http4K, Quarkus, Micronaut, Vert.x, Heildon, or Jooby.
            // For this demo, it is in the same JVM, but it could be as easily be
            //      * in a separate JVM/process,
            //      * or in another machine / VM / host
            //      * or many provisioned (explicit horizontal scaling, auto-scaled cluster, combination of those)
            MuServer helloWorldExampleApp = MuServerBuilder.httpServer()
                    .addHandler(Method.GET, helloWorldAppPathPrefix, (request, response, pathParams) -> {
                        response.write("Hello, world");
                    })
                    .start();
            System.out.println("HelloWorld server (internal network) at " + helloWorldExampleApp.uri() + "/" + helloWorldAppPathPrefix + " (try it)");

             // Each deployment of the business app or service would need to register itself with the cranker:

            CrankerConnector connector = CrankerConnectorBuilder.connector()
                    .withRouterRegistrationListener(new RouterEventListener() {
                        public void onRegistrationChanged(ChangeData data) {
                            //System.out.println("Router registration changed: " + data);
                        }
                        public void onSocketConnectionError(RouterRegistration router, Throwable exception) {
                            //System.out.println("\nError connecting to " + router + " - " + exception.getMessage() + "\n");
                        }
                    })
                    .withRouterLookupByDNS(URI.create(webSocketRoutedTrafficHookup))
                    .withRoute(helloWorldAppPathPrefix)  // don't have "/" as first char of prefix
                    .withTarget(helloWorldExampleApp.uri())
                    .start();

            System.out.println("Cranker routed traffic hookup " + webSocketRoutedTrafficHookup);
            String crankedHelloWorldUrl = crankerPublicWebServerUri + "/" + helloWorldAppPathPrefix;
            System.out.println("Cranked HelloWorld is available in public at " + crankedHelloWorldUrl + " (try it)");
            System.out.println("Hit ctrl-c to bring down webservers");
            System.out.println("===================================");

            threadPoolHammeringOfEndpoint(crankedHelloWorldUrl, 40000, "(cranked & SSL) ");

            threadPoolHammeringOfEndpoint(helloWorldExampleApp.uri() + "/" + helloWorldAppPathPrefix, 400000, "(uncranked & non SSL) ");

            System.out.println("Tests Finished");

        }
    }

    private static void threadPoolHammeringOfEndpoint(String appToTestUrl, int totalRequests, String crankedOrNot) throws MalformedURLException {

        System.out.println("Thread-pool hammering of the "+ crankedOrNot + "URL: " + appToTestUrl + " ...");


        AtomicInteger successfulRequests = new AtomicInteger(0);
        AtomicInteger unsuccessfulRequestsNot404 = new AtomicInteger(0);
        AtomicInteger fourOhFourRequests = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(10);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            executor.execute(() -> {
                try {
                    URL url = new URL(appToTestUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");


                    // https://stackoverflow.com/questions/686217/maximum-on-http-header-values#:~:text=No%2C%20HTTP%20does%20not%20define,headers%20size%20exceeds%20that%20limit.
                    //

                    connection.setRequestProperty("CONTRIVEDHEADDR", HEADERCHARS);

                    int responseCode = connection.getResponseCode();

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                        String inputLine;
                        StringBuilder content = new StringBuilder();

                        while ((inputLine = in.readLine()) != null) {
                            content.append(inputLine);
                        }

                        in.close();
                        connection.disconnect();

                        if ("Hello, world".equals(content.toString())) {
                            successfulRequests.incrementAndGet();
                        } else {
                            unsuccessfulRequestsNot404.incrementAndGet();
                        }
                    } else {
                        if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                            fourOhFourRequests.incrementAndGet();
                        } else {
                            unsuccessfulRequestsNot404.incrementAndGet();
                        }
                    }
                } catch (IOException e) {
                    unsuccessfulRequestsNot404.incrementAndGet();
                }
            });
        }

        executor.shutdown();
        while (!executor.isTerminated()) {
            // Wait for all threads to finish
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        double requestsPerSecond = (successfulRequests.get() * 1000.0) / duration;

        System.out.println("Total Successful Requests: " + successfulRequests.get());

        if (fourOhFourRequests.get() > 0 || unsuccessfulRequestsNot404.get() > 0) {
            System.out.println("Total Unsuccessful Requests (not 404): " + unsuccessfulRequestsNot404.get());
            System.out.println("Total Unsuccessful Requests: (404): " + fourOhFourRequests.get());
        }
        if (duration < 60000) {
            System.out.println("Total Time: " + (duration / 1000) + " secs");
        } else {
            System.out.printf("Total Time: %.2f mins\n", ((float) (duration / 1000) / 60));
        }
        System.out.println("... Requests per second: " + Math.round(requestsPerSecond));
    }

}
