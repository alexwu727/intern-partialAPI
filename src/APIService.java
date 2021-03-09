import aiello.keeper.core.api.controller.BaseController;
import io.swagger.v3.oas.models.OpenAPI;
import io.vertx.core.*;
import io.vertx.core.eventbus.*;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.TimeoutHandler;
import io.vertx.ext.web.sstore.SessionStore;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

public class APIService extends AbstractVerticle {
    private final Logger log = LoggerFactory.getLogger(APIService.class);
    private HttpServer server;
    private OpenAPI openAPI;
    private Router router;

    private final Map<String, List<String>> pathMap = new HashMap<>(); // example: {"^/api/a/b$" : ["/api/a/", "b08a5232-c42f-436a-b96b-244ae8af2bc3"]}

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        EventBus eventBus = vertx.eventBus();
        System.out.println("----------------- Successfully deployed APIService verticle -------------------");

        // deploy controllers
        deployControllers();

        // get controller paths
        eventBus.consumer("apiGate", receivedMessage ->{
            JsonObject msg = (JsonObject) receivedMessage.body();
            JsonArray completePathArray = msg.getJsonArray("completePathArray");
            // compare to the old version of pathMap
            System.out.println("comparing... ");
            for (Object completePath : completePathArray){
                if (pathMap.containsKey(getPathPattern((String) completePath, false))) {
                    int failCode = 73; // failCode tbd
                    System.out.println("conflicted happened: " + completePath.toString());
                    receivedMessage.fail(failCode, "conflicted happened");
                    return;
                }
            }

            // update pathMap
            System.out.println("updating... ");
            for (Object completePath : completePathArray){
                String[] pathMapValue = {msg.getString("apiPath"), msg.getString("apiUuid")};
                pathMap.put(getPathPattern((String) completePath, false), Arrays.asList(pathMapValue));
            }
            receivedMessage.reply("------------ Successfully received eventbus address from controller ------------");
            System.out.println(pathMap);
        });

        // initial router and register eventbus address by apiPathMap
        try {
            initialRouter();
        } catch (Exception e) {
            String msg = "Failed to initialize HTTP router.";
            log.error(msg, e);
            startPromise.fail(msg);
            return;
        }

        // create http server
        server = vertx.createHttpServer(
                new HttpServerOptions(config())).requestHandler(router).listen(ar -> {
                    if (ar.succeeded()) {
                        log.info("API service is running by listening in " + ar.result().actualPort());
                        startPromise.complete();
                    } else {
                        startPromise.fail(new VertxException("Failed to start API service.", ar.cause()));
                    }
                });
    }

    @Override
    public void stop(Promise<Void> stopPromise) throws Exception {
        server.close(ar -> {
            if (ar.succeeded()) {
                log.info("API service listening in {} is stopped.", Integer.toString(server.actualPort()));
                stopPromise.complete();
            } else {
                stopPromise.fail(new VertxException("Failed to stop API service.", ar.cause()));
            }
        });
    }

    private void deployControllers(){
        ServiceLoader<BaseController> loader = ServiceLoader.load(BaseController.class, this.getClass().getClassLoader());
        System.out.println("deploying controllers...");
        for (BaseController controller : loader) {
            vertx.deployVerticle(controller);
        }
    }

    private void initialRouter() throws Exception {
        router = Router.router(vertx);
        Route route = router.routeWithRegex(".+"); //".+api.+");
        route.handler(SessionHandler.create(SessionStore.create(vertx)));
        route.handler(TimeoutHandler.create());
        route.handler(BodyHandler.create());

        // API gate route
        route.handler(rc ->{
            EventBus eventBus = vertx.eventBus();
            HttpServerRequest request = rc.request();

            // path
            JsonObject paths = new JsonObject();
            paths.put("routePath", request.path());
            setPaths(paths);
            if (!paths.getValue("isValid").equals(true)){
                rc.fail(404);
            }
            else{
                // parameters
                JsonObject params_json = new JsonObject();
                setPathParam(paths.getString("routePathMatchedPart"), paths.getString("apiPath"), params_json); // path parameters
                MultiMap queryParams_map =  rc.queryParams();  // query parameters
                for (Map.Entry<String, String> entry : queryParams_map.entries()) {
                    params_json.put(entry.getKey(), entry.getValue());
                }
                // request body
                JsonObject requestBody = rc.getBodyAsJson();

                // construct massage to send to controller
                JsonObject message = new JsonObject();
                message.put("httpMethod", request.method().toString());
                message.put("paths", paths);
                message.put("parameters", params_json);
                message.put("requestBody", requestBody);

                eventBus.request(paths.getString("apiAddress"), message, new DeliveryOptions().setHeaders(request.headers()).setSendTimeout(10000), reply ->{
                    if (reply.succeeded()){
                        rc.response().setChunked(true);
                        ReplyException exception = (ReplyException) reply.cause();
                        if (exception != null) {
                            System.out.println("-------------------------- failure code --------------------------");
                            rc.response().setStatusCode(exception.failureCode());
                            System.out.println(exception.toString());
                            rc.response().write(exception.toString());
                        }
                        JsonObject replyMsg = (JsonObject) reply.result().body();
                        if (replyMsg.containsKey("headerContentType")) rc.response().putHeader(HttpHeaders.CONTENT_TYPE, replyMsg.getString("headerContentType"));
                        // status code 200 or 201
                        if (replyMsg.containsKey("statusCode")) rc.response().setStatusCode(replyMsg.getInteger("statusCode"));
                        rc.response().write(replyMsg.getString("body"));
                        rc.response().end();
                    } else {
                        ReplyException exception = (ReplyException) reply.cause();

                        System.out.println("-------------------------- failure code --------------------------");
                        rc.response().setStatusCode(exception.failureCode());
                        System.out.println(exception.toString());
                        rc.response().end(exception.toString());
                    }

                });
            }
        });

        // Route to get swagger definition
//        router.route(HttpMethod.GET, "/swagger/v1/swagger.json").handler(rc -> {
//            String schema = rc.request().scheme();
//            String host = rc.request().host();
//            rc.response().headers().add("Content-Type", "application/json");
//            List<Server> servers = openAPI.getServers();
//            if (Objects.isNull(servers)) {
//                servers = new ArrayList<>();
//                openAPI.setServers(servers);
//                Server server = new Server();
//                server.url(schema + "://" + host);
//                servers.add(server);
//            } else {
//                for (Server server : servers) {
//                    if (!server.getUrl().matches("^" + schema + "://.*")) {
//                        server.setUrl(schema + "://" + server.getUrl());
//                    }
//                }
//            }
//            rc.response().end(io.swagger.v3.core.util.Json.pretty(openAPI));
//        });
//        router.route(HttpMethod.GET, "/swagger").handler(rc -> {
//            rc.reroute("/swagger/v1/swagger.json");
//        });

//        for (Route r : router.getRoutes()) {
//            // Path is public, but methods are not. We change that
//            Field f = r.getClass().getDeclaredField("methods");
//            f.setAccessible(true);
//            Set<HttpMethod> methods = (Set<HttpMethod>) f.get(r);
//            System.out.println(methods.toString() + r.getPath());
//        }
    }
    public static String getPathPattern(String apiPath, boolean isApiPath){
        String regexPattern;
        if (apiPath.contains("{")) regexPattern = apiPath.replaceAll("\\{\\w+}", "\\\\w+");
        else regexPattern = apiPath;
        if (isApiPath) return "^" + regexPattern;
        return "^" + regexPattern + "$";
    }
    private void setPaths(JsonObject paths) {
        // route path
        String routePath = paths.getValue("routePath").toString();
        for (String patternString: pathMap.keySet()){
            // find which api path first
            Pattern p = Pattern.compile(patternString);
            Matcher m = p.matcher(routePath);
            if (m.find()) {
                String apiPath = pathMap.get(patternString).get(0);
                // get matched part and offset
                Pattern pattern = Pattern.compile(getPathPattern(apiPath, true));
                Matcher matcher = pattern.matcher(routePath);
                if (matcher.find()){
                    paths.put("isValid", true);
                    paths.put("routePathMatchedPart", matcher.group(0));
                    paths.put("apiPath", apiPath);
                    paths.put("apiAddress", pathMap.get(patternString).get(1));
                    paths.put("offset", "/" + routePath.substring(matcher.group(0).length()));
                    System.out.println("----------------- paths -----------------");
                    System.out.println(paths.toString());
                    return;
                }
            }
        }
        paths.put("isValid", false);
    }
    private void setPathParam(String routePathMatchedPart, String apiPath, JsonObject params_json){
        if (apiPath.contains("{")){
            List<String> p1 = Arrays.asList(apiPath.split("/"));
            List<String> p2 = Arrays.asList(routePathMatchedPart.split("/"));
            IntStream.range(0, p1.size()).forEachOrdered(n -> {
                if (p1.get(n).contains("{")) params_json.put(p1.get(n).substring(1, p1.get(n).length() - 1), p2.get(n));
            });
        }
    }
    public static void main (String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new APIService());
    }
}
