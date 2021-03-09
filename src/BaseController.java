import aiello.keeper.core.api.ClientException;
import io.swagger.v3.core.util.ReflectionUtils;
import io.swagger.v3.jaxrs2.ext.OpenAPIExtensions;
import io.swagger.v3.jaxrs2.util.ReaderUtils;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.vertx.core.*;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.ext.web.RoutingContext;

import javax.ws.rs.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

public abstract class BaseController extends AbstractVerticle {
    protected Logger log;
    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_RED_BACKGROUND = "\u001B[41m";
    public BaseController() {}
    UUID apiUuid = UUID.randomUUID();
    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        // get this controller's name for following use
        String controllerName = this.getClass().getSimpleName();
        System.out.println("--------- Deploying "+controllerName+" ---------");

        // create eventbus
        EventBus eventBus = vertx.eventBus();

        // tell api service path information
        sendApiPath(eventBus, controllerName, startPromise);

        // main consume part: receive request, invoke method and response
        setEventBus(eventBus);

        // deploy fail section
        if (isOptionalParamsNonnullType()) throw new Exception("Non-required parameters DO NOT use non-null type!");

    }

    public void sendApiPath(EventBus eventBus, String controllerName, Promise<Void> startPromise){
        JsonObject msg = new JsonObject();
        // completePath = mainString + offsetString
        Path apiPath = ReflectionUtils.getAnnotation(this.getClass(), Path.class);
        String mainString = apiPath.value().substring(0, apiPath.value().length()-1);

        // completePathArray
        List<String> completePathArray = new ArrayList<>();
        for (Method method : this.getClass().getMethods()){
            Path offsetPath = ReflectionUtils.getAnnotation(method, Path.class);
            if (offsetPath != null){
                String offsetString = offsetPath.value();
                completePathArray.add(mainString + offsetString);
            }
        }
        // construct massage to send to APIService
        msg.put("apiUuid", apiUuid.toString());
        msg.put("apiPath", apiPath.value());
        msg.put("completePathArray", completePathArray);
        eventBus.request("apiGate", msg, reply ->{
            if (reply.succeeded()){
                System.out.println("--------- Successfully deployed controller " + controllerName + " ---------");
                startPromise.complete();
            } else {
                System.out.println("--------- Failed to deploy controller " + controllerName + " ---------");
                startPromise.fail(reply.cause());
            }
        });
    }

    public void setEventBus(EventBus eventBus){
        eventBus.<JsonObject>consumer(apiUuid.toString(), receivedMessage -> {
            // get variable from received message.  (paths, http method, parameters, request body)
            JsonObject paths = (JsonObject) receivedMessage.body().getValue("paths");
            String offsetFromRequest = paths.getString("offset");
            String httpMethodFromRequest = (String) receivedMessage.body().getValue("httpMethod");
            int statusCode = (httpMethodFromRequest.equals("POST")) ? 201 : 200;
            JsonObject parametersJsonFromRequest = (JsonObject) receivedMessage.body().getValue("parameters");
            JsonObject requestBodyFromRequest = (JsonObject) receivedMessage.body().getValue("requestBody");
            JsonObject response = new JsonObject();

            // from method's path and http method to find correct method we want to invoke.
            boolean notFound = true;
            for (Method method : this.getClass().getMethods()) {
                // find correct http method
                String httpMethodFromMethod = ReaderUtils.extractOperationMethod(method, OpenAPIExtensions.chain());
                if (null == httpMethodFromMethod || httpMethodFromMethod.length() == 0) continue;
                if (!Objects.equals(httpMethodFromRequest.toUpperCase(), httpMethodFromMethod.toUpperCase())) continue;

                // find correct path
                Path offsetPath = ReflectionUtils.getAnnotation(method, Path.class);
                String offsetFromMethod = offsetPath.value();
                if (offsetFromMethod.contains("{")){
                    String regexPattern = offsetFromMethod.replaceAll("\\{\\w+}", "\\\\w+");
                    if (!offsetFromRequest.matches(regexPattern)) continue;
                    // set offset path parameter
                    setPathParam(offsetFromRequest, offsetFromMethod, parametersJsonFromRequest);
                }
                else {
                    if (!Objects.equals(offsetFromRequest, offsetFromMethod)) continue;
                }

                notFound = false;

                // create parameters array
                List<Object> paramsList = new ArrayList<>();
                for (Parameter param : method.getParameters()){
                    io.swagger.v3.oas.annotations.Parameter swaggerParam = param.getAnnotation(io.swagger.v3.oas.annotations.Parameter.class);

                    if (swaggerParam != null){
                        Class<?> type = param.getType();
                        String value = (String) parametersJsonFromRequest.getValue(swaggerParam.name());
                        if (value == null && swaggerParam.required()){
                            receivedMessage.fail(400, "Required parameter missed.");
                            return;
                        }
                        try {
                            paramsList.add(processParameter(type, value));
                        } catch (Exception e) {
                            receivedMessage.fail(400, e.toString());
                            return;
                        }
                    }
                }

                // request body
                RequestBody requestBodyFromMethod = ReflectionUtils.getAnnotation(method, RequestBody.class);
                if (null != requestBodyFromMethod){
                    if (!(Objects.isNull(requestBodyFromRequest) || requestBodyFromRequest.isEmpty())){
                        paramsList.add(requestBodyFromRequest);
                    }
                }

                // arg complete
                System.out.println(paramsList);

                // invoke method
                try {
                    Object resultHandler = method.invoke(this, paramsList.toArray());
                    if (!Objects.isNull(resultHandler))
                        response.put("body", resultHandler);
                    if (resultHandler instanceof Future) {
                        ((Future<?>) resultHandler).onComplete(ar -> {
                            if (ar.succeeded()) {
                                Object result = ar.result();
                                if (result instanceof JsonObject || result instanceof JsonArray)
                                    response.put("headerContentType", "application/json");
                                else
                                    response.put("headerContentType", "text/plain");
                            } else {
                                if (ar.cause() instanceof ClientException)
                                    receivedMessage.fail(400, ar.cause().toString());
                                else
                                    receivedMessage.fail(500, ar.cause().toString());
                            }
                        });
                    }
                } catch (InvocationTargetException e) {
                    if (e.getTargetException() instanceof ClientException)
                        receivedMessage.fail(400, e.toString());
                    else
                        receivedMessage.fail(500, e.toString());
                    log.error("Exception on " + method.getName(), e);
                } catch (Throwable e) {
                    receivedMessage.fail(500,  e.toString());
                    log.error("Failure to invoke " + method.getName(), e);
                }
            }
            if (notFound) receivedMessage.fail(404, "Not Found");
            // construct response
//            receivedMessage.fail(500, );
//            ReplyException re = new ReplyException(ReplyFailure.fromInt(statusCode));
            response.put("statusCode", statusCode);
            receivedMessage.reply(response);
        });
    }

    public boolean isOptionalParamsNonnullType(){
        boolean res = false;
        for (Method method : this.getClass().getMethods()) {
            String httpMethodFromMethod = ReaderUtils.extractOperationMethod(method, OpenAPIExtensions.chain());
            if (null == httpMethodFromMethod || httpMethodFromMethod.length() == 0) continue;

            for (Parameter parameter : method.getParameters()) {
                io.swagger.v3.oas.annotations.Parameter param = parameter.getAnnotation(io.swagger.v3.oas.annotations.Parameter.class);
                if (param == null) continue;
                if (param.required()) continue;
                if (parameter.getType().equals(int.class) || parameter.getType().equals(boolean.class)) {
                    System.out.println(ANSI_RED_BACKGROUND+"============================ warning ============================"+ ANSI_RESET);
                    System.out.println("There's a non-required parameter using non-null type!");
                    System.out.println("----------------------------------------------------");
                    System.out.println("method: " + method.getName() + "\narg: " + parameter.getName() + "\ntype: " + parameter.getType());
                    res = true;
                }
            }
        }
        return res;
    }
    private void setPathParam(String routePathMatchedPart, String apiAddress, JsonObject params_json){
        if (apiAddress.contains("{")){
            List<String> p1 = Arrays.asList(apiAddress.split("/"));
            List<String> p2 = Arrays.asList(routePathMatchedPart.split("/"));
            IntStream.range(0, p1.size()).forEachOrdered(n -> {
                if (p1.get(n).contains("{")) params_json.put(p1.get(n).substring(1, p1.get(n).length() - 1), p2.get(n));
            });
        }
    }
    @Override
    public void stop(Promise<Void> stopPromise) throws Exception {
        String controllerName = this.getClass().getSimpleName();
        System.out.println("--------- Controller " + controllerName + " stopped ---------");
    }
    private static void setStatusCode(RoutingContext context, int code, Throwable cause, boolean isDebugEnabled) {
        if (isDebugEnabled)
            context.fail(code, cause);
        else
            context.fail(code);
    }

    private static String path(String rawPath) {
        int braces = 0;
        String partPath = "", finalPath = "";
        for (char c : rawPath.toCharArray()) {
            switch (c) {
                case '{':
                    braces++;
                    break;
                case '}':
                    braces--;
                    break;
                case '/':
                    if (braces == 0) {
                        int length = partPath.length();
                        if (length > 2) {
                            if (partPath.matches("^\\{.*\\}$")) {
                                partPath = partPath.substring(1, length - 1);
                                int index = partPath.indexOf(":");
                                if (index > 0) {
                                    partPath = "(?" + partPath.substring(0, index)
                                            + partPath.substring((index + 1)) + ")";
                                } else
                                    partPath = ":" + partPath;
                            }
                        }
                        finalPath += partPath + c;
                        partPath = "";
                    }
                    continue;
            }
            partPath += c;
        }
        int length = partPath.length();
        if (length > 2) {
            if (partPath.matches("^\\{.*\\}$")) {
                partPath = partPath.substring(1, length - 1);
                int index = partPath.indexOf(":");
                if (index > 0) {
                    partPath = "(?<" + partPath.substring(0, index).trim()  + ">"
                            + partPath.substring((index + 1)).trim() + ")";
                } else
                    partPath = ":" + partPath.trim() ;
            }
        }
        finalPath += partPath;
        return finalPath;
    }

    protected Object processParameter(Class<?> type, String value) {
        Object obj;
        if (Objects.isNull(value))
            obj = null;
        else if (type == Boolean.TYPE || type == Boolean.class)
            obj = Boolean.parseBoolean(value);
        else if (type == Date.class)
            obj = Date.from(Instant.parse(value));
        else if (type == Double.TYPE || type == Double.class)
            obj = Double.parseDouble(value);
        else if (type == Float.TYPE || type == Float.class)
            obj = Long.parseLong(value);
        else if (type == Integer.TYPE || type == Integer.class)
            obj = Integer.parseInt(value);
        else if (type == Long.TYPE || type == Long.class)
            obj = Long.parseLong(value);
        else if (type == Short.TYPE || type == Short.class)
            obj = Short.parseShort(value);
        else
            obj = value;
        return obj;
    }

    protected String buildFilter(JsonObject parameters) {
        StringBuilder builder = new StringBuilder("{");
        parameters.forEach(entry -> {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof CharSequence)
                builder.append(String.format("%s:\"%s\"", key, value));
            else if (value instanceof JsonObject) {
                builder.append(String.format("%s:%s", key, buildFilter((JsonObject) value)));
            } else
                builder.append(String.format("%s:%s", key, value));
            builder.append(",");
        });
        return builder.deleteCharAt(builder.length() - 1).append("}").toString();
    }
}
