import com.hsbc.cranker.connector.CrankerConnector;
import com.hsbc.cranker.connector.RouterEventListener;
import com.hsbc.cranker.connector.RouterRegistration;
import com.hsbc.cranker.mucranker.CrankerRouter;
import com.hsbc.cranker.mucranker.CrankerRouterBuilder;
import com.hsbc.cranker.connector.CrankerConnectorBuilder;
import io.muserver.Method;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    public static void main(String[] args) throws MalformedURLException {

        URI crankerPublicWebServerUri = null;
        URI crankerDmzRegistrationURI = null;
        String webSocketRoutedTrafficHookup = null;

        // Cranker setup
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

            // TODO

            System.out.println("Cranker routed traffic hookup " + webSocketRoutedTrafficHookup);
            String crankedHelloWorldUrl = crankerPublicWebServerUri + "/" + helloWorldAppPathPrefix;
            System.out.println("Cranked HelloWorld is available in public at " + crankedHelloWorldUrl + " (try it)");
            System.out.println("Hit ctrl-c to bring down webservers");
            System.out.println("===================================");

            //singleThreadedHammeringOfEndpoint(crankedHelloWorldUrl, 40000, "(cranked & SSL) ");
            threadPoolHammeringOfEndpoint(crankedHelloWorldUrl, 40000, "(cranked & SSL) ");
            //hammerWithVirtualThreads(crankedHelloWorldUrl, 40000, "(cranked & SSL) ");

            //singleThreadedHammeringOfEndpoint(helloWorldExampleApp.uri() + "/" + helloWorldAppPathPrefix, 80000, "(uncranked & non SSL) ");
            threadPoolHammeringOfEndpoint(helloWorldExampleApp.uri() + "/" + helloWorldAppPathPrefix, 400000, "(uncranked & non SSL) ");
            // hammerWithVirtualThreads(helloWorldExampleApp.uri() + "/" + helloWorldAppPathPrefix, 400000, "(uncranked & non SSL) ");

            System.out.println("Tests Finished");

        }
    }
    private static void singleThreadedHammeringOfEndpoint(String appToTestUrl, int totalRequests, String crankedOrNot) throws MalformedURLException {

        System.out.println("Single-threaded hammering of the "+ crankedOrNot + "URL: " + appToTestUrl + " ...");

        illAdvisedAcceptSelfSignedCerts();

        int successfulRequests = 0;
        int unSuccessfulRequests = 0;

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            URL url = new URL(appToTestUrl);
            try {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                int responseCode = connection.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    String inputLine;
                    StringBuilder content = new StringBuilder();

                    while ((inputLine = in.readLine()) != null) {
                        content.append(inputLine);
                    }

                    // Close connections
                    in.close();
                    connection.disconnect();

                    // Check if the response is "hello, world"
                    if ("Hello, world".equals(content.toString())) {
                        successfulRequests++;
                    } else {
                        unSuccessfulRequests++;
                    }
                }
            } catch (IOException e) {
                unSuccessfulRequests++;
            }

        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        double requestsPerSecond = (successfulRequests * 1000.0) / duration;

        if (unSuccessfulRequests > 0) {
            System.out.println("Total Successful Requests: " + successfulRequests);
            System.out.println("Total Unsuccessful Requests: " + unSuccessfulRequests);
            System.out.println("Total Time: " + duration + " ms");
        }
        System.out.println("... Requests per second: " + Math.round(requestsPerSecond));
    }

    private static void threadPoolHammeringOfEndpoint(String appToTestUrl, int totalRequests, String crankedOrNot) throws MalformedURLException {

        System.out.println("Thread-pool hammering of the "+ crankedOrNot + "URL: " + appToTestUrl + " ...");

        illAdvisedAcceptSelfSignedCerts();

        AtomicInteger successfulRequests = new AtomicInteger(0);
        AtomicInteger unsuccessfulRequests = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(100);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            executor.execute(() -> {
                try {
                    URL url = new URL(appToTestUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");

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
                            unsuccessfulRequests.incrementAndGet();
                        }
                    } else {
                        unsuccessfulRequests.incrementAndGet();
                    }
                } catch (IOException e) {
                    unsuccessfulRequests.incrementAndGet();
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

        if (unsuccessfulRequests.get() > 0) {
            System.out.println("Total Successful Requests: " + successfulRequests.get());
            System.out.println("Total Unsuccessful Requests: " + unsuccessfulRequests.get());
            System.out.println("Total Time: " + duration + " ms");
        }
        System.out.println("... Requests per second: " + Math.round(requestsPerSecond));
    }

    private static void hammerWithVirtualThreads(String appToTestUrl, int totalRequests, String crankedOrNot) throws MalformedURLException {

        System.out.println("Virtual thread hammering of the "+ crankedOrNot + "URL: " + appToTestUrl + " ...");

        illAdvisedAcceptSelfSignedCerts();

        AtomicInteger successfulRequests = new AtomicInteger(0);
        AtomicInteger unsuccessfulRequests = new AtomicInteger(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    URL url = new URL(appToTestUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");

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
                            unsuccessfulRequests.incrementAndGet();
                        }
                    } else {
                        unsuccessfulRequests.incrementAndGet();
                    }
                } catch (IOException e) {
                    unsuccessfulRequests.incrementAndGet();
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

        if (unsuccessfulRequests.get() > 0) {
            System.out.println("Total Successful Requests: " + successfulRequests.get());
            System.out.println("Total Unsuccessful Requests: " + unsuccessfulRequests.get());
            System.out.println("Total Time: " + duration + " ms");
        }
        System.out.println("... Requests per second: " + Math.round(requestsPerSecond));
    }

    private static void illAdvisedAcceptSelfSignedCerts() {

        // Set up a TrustManager that trusts all certificates
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
        };

        // Install the all-trusting trust manager
        SSLContext sc = null;
        try {
            sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (KeyManagementException e) {
            throw new RuntimeException(e);
        }
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

        // Create all-trusting host name verifier
        HostnameVerifier allHostsValid = new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) {
                return true;
            }
        };

        // Install the all-trusting host verifier
        HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);

    }

}
