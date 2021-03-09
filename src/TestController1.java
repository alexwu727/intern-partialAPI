import aiello.keeper.core.ServiceManager;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.LoggerFactory;

import javax.ws.rs.*;
import java.util.Objects;

@Path("/api/hotels/{hotel}/users/")
public class TestController1 extends BaseController {

    {
        log = LoggerFactory.getLogger(TestController1.class);
    }

    @GET
    @Path("/")
    @ApiResponse(
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(
                            schema = @Schema(
//                                    implementation = User.class
                            )
                    )
            )
    )
    public Future<JsonObject> getUsers(
            @Parameter(in = ParameterIn.PATH) @PathParam("hotel") String hotel,
            @Parameter(in = ParameterIn.QUERY, required = false) @QueryParam("blocked") Boolean blocked,
            @Parameter(in = ParameterIn.QUERY, required = false) @QueryParam("confirmed") Boolean confirmed,
            @Parameter(in = ParameterIn.QUERY, required = false) @QueryParam("email") String email
    ) {
        System.out.println("================================ method path '/' ================================ ");
        return getUsers(hotel, null, blocked, confirmed, email);
    }
    @POST
    @Path("/hello/{username}/world/{test2}/{test3}")
    @ApiResponse(
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(
                            schema = @Schema(
//                                    implementation = User.class
                            )
                    )
            )
    )
    public String testMethod(
            @Parameter(in = ParameterIn.PATH, name = "hotel") @PathParam("hotel") String hotel,
            @Parameter(in = ParameterIn.PATH, name = "username") @PathParam("username") String username,
            @Parameter(in = ParameterIn.QUERY, required = true, name = "age1") @QueryParam("age1") int age1,
            @Parameter(in = ParameterIn.QUERY, required = false, name = "age2") @QueryParam("age2") Integer age2,
            @Parameter(in = ParameterIn.QUERY, required = false, name = "isHandsome") @QueryParam("isHandsome") Boolean isHandsome,
            @Parameter(in = ParameterIn.QUERY, required = false, name = "email") @QueryParam("email") String email
    ) {
//        int width = 50;
//        int largerWidth = 80;
//        String equalSign = "=";
//        String repeated = equalSign.repeat(3);
//        System.out.println(repeated);
//        System.out.println("=============== test method ===============");
//        System.out.println("|hotel: " + hotel + " ".repeat(width - hotel.length() - 8) + "|");
//        System.out.println("|username: " + username  + " ".repeat(width - username.length() - 11) + "|");
//        System.out.println("|age1: " + age1  + " ".repeat(width - Integer.toString(age1).length() - 7) + "|");
//        System.out.println("|age2: " + age2  + " ".repeat(width - age2.toString().length() - 7) + "|");
//        System.out.println("|email: " + email  + " ".repeat(width - email.length() - 8) + "|");
//        System.out.println("|isHandsome: "+isHandsome  + " ".repeat(width - isHandsome.toString().length() - 13) + "|");
//        System.out.println(equalSign.repeat(width));
        String responseString = "hotel " + hotel + " has a user called " + username + ", and his/her age is " + age1;
        return responseString;
    }

    @GET
    @Path("/{username}")
    @ApiResponse(
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
//                            implementation = User.class
                    )
            )
    )
    public Future<JsonObject> getUser(
            @Parameter(in = ParameterIn.PATH) @PathParam("hotel") String hotel,
            @Parameter(in = ParameterIn.PATH) @PathParam("username") String username
    ) {
        System.out.println("================================ method path \"/{username}\" ================================ ");
        return getUsers(hotel, username, null, null, null).compose(ar -> {
            Promise<JsonObject> promise = Promise.promise();

            JsonArray data = ar.getJsonArray("data", new JsonArray());
            if (data.size() == 0) {
                ar.putNull("data");
            } else {
                ar.put("data", data.getJsonObject(0));
            }

            promise.complete(ar);
            return promise.future();
        });
    }

    private Future<JsonObject> getUsers(
            String hotel,
            String username,
            Boolean blocked,
            Boolean confirmed,
            String email
    ) {
        Promise<JsonObject> promise = Promise.promise();

        JsonObject parameters = new JsonObject();
        if (Objects.nonNull(blocked))
            parameters.put("blocked", blocked);
        if (Objects.nonNull(confirmed))
            parameters.put("confirmed", confirmed);
        if (Objects.nonNull(email))
            parameters.put("email", email);
        if (Objects.nonNull(username))
            parameters.put("username", username);
        if (Objects.nonNull(hotel))
            parameters.put("hotel", new JsonObject().put("name", hotel));

        StringBuilder whereBuilder = new StringBuilder();
        if (parameters.size() > 0) {
            whereBuilder.append("(filter: ");
            whereBuilder.append(buildFilter(parameters));
            whereBuilder.append(")");
        }

        String query = "{ user %s {blocked, confirmed, email, fullname, language, role, username}}";
        System.out.println(query);
        query = String.format(query, whereBuilder.toString());
        log.debug("query: {}", query);

        ServiceManager.<JsonObject>requestToNeo4j(
                query,
                ar -> {
                    if (ar.succeeded()) {
                        JsonObject result = ar.result();
                        result.put("data", result.remove("user"));
                        promise.complete(result);
                    } else {
                        log.error("Executed is failed.", ar.cause());
                        promise.fail(ar.cause());
                    }
                }
        );
        return promise.future();
    }



    @POST
    @Path("/{username}/auth")
    @ApiResponse(
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
//                            implementation = User.class
                    )
            )
    )
    public Future<JsonObject> Authentication(
            @Parameter(in = ParameterIn.PATH) @PathParam("hotel") String hotel,
            @Parameter(in = ParameterIn.PATH) @PathParam("username") String username
//            @RequestBody AuthenticationBody body
    ) {
        System.out.println("================================ method path \"/{username}/auth\" ================================ ");
        Promise<JsonObject> promise = Promise.promise();
//        String query = "{ user %s {blocked, confirmed, email, fullname, language, role, username, web {icon, key, name, path, type, permission {readable, writable }}}}";
//        JsonObject parameters = new JsonObject();
//        if (Objects.nonNull(hotel))
//            parameters.put("hotel", new JsonObject().put("name", hotel));
//        if (Objects.nonNull(username))
//            parameters.put("username", username);
//        if (Objects.nonNull(body) && Objects.nonNull(body.getPassword())) {
//            try {
//                parameters.put("password", Hash.encrypt(body.getPassword(), "SHA-256"));
//            } catch (NoSuchAlgorithmException e) {
//                promise.fail(e);
//                return promise.future();
//            }
//        }
//
//        StringBuilder whereBuilder = new StringBuilder();
//        if (parameters.size() > 0) {
//            whereBuilder.append("(filter: ");
//            whereBuilder.append(buildFilter(parameters));
//            whereBuilder.append(")");
//        }
//
//        query = String.format(query, whereBuilder.toString());
//        log.debug("query: {}", query);
//
//        ServiceManager.<JsonObject>requestToNeo4j(
//                query,
//                ar -> {
//                    if (ar.succeeded()) {
//                        JsonObject result = ar.result();
//                        result.put("data", result.remove("user"));
//                        JsonArray data = result.getJsonArray("data", new JsonArray());
//                        if (data.size() == 0) {
//                            result.putNull("data");
//                        } else
//                            result.put("data", data.getJsonObject(0));
//                        promise.complete(result);
//                    } else {
//                        log.error("Executed is failed.", ar.cause());
//                        promise.fail(ar.cause());
//                    }
//                }
//        );

        return promise.future();
    }
}
