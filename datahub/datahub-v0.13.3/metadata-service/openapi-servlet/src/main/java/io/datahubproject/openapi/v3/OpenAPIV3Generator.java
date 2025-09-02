package io.datahubproject.openapi.v3;

import static io.datahubproject.openapi.util.ReflectionCache.toUpperFirst;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.fge.processing.ProcessingUtil;
import com.google.common.collect.ImmutableMap;
import com.linkedin.data.avro.SchemaTranslator;
import com.linkedin.metadata.models.AspectSpec;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.models.registry.EntityRegistry;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings({"rawtypes", "unchecked"})
public class OpenAPIV3Generator {
  private static final String MODEL_VERSION = "_v3";
  private static final String TYPE_OBJECT = "object";
  private static final String TYPE_BOOLEAN = "boolean";
  private static final String TYPE_STRING = "string";
  private static final String TYPE_ARRAY = "array";
  private static final String TYPE_INTEGER = "integer";
  private static final String NAME_QUERY = "query";
  private static final String NAME_PATH = "path";
  private static final String NAME_SYSTEM_METADATA = "systemMetadata";
  private static final String NAME_ASYNC = "async";
  private static final String NAME_SCROLL_ID = "scrollId";
  private static final String PROPERTY_VALUE = "value";
  private static final String PROPERTY_URN = "urn";
  private static final String PROPERTY_PATCH = "patch";
  private static final String PROPERTY_PATCH_PKEY = "arrayPrimaryKeys";
  private static final String PATH_DEFINITIONS = "#/components/schemas/";
  private static final String FORMAT_PATH_DEFINITIONS = "#/components/schemas/%s%s";
  private static final String ASPECT_DESCRIPTION = "Aspect wrapper object.";
  private static final String REQUEST_SUFFIX = "Request" + MODEL_VERSION;
  private static final String RESPONSE_SUFFIX = "Response" + MODEL_VERSION;

  private static final String ASPECT_REQUEST_SUFFIX = "Aspect" + REQUEST_SUFFIX;
  private static final String ASPECT_RESPONSE_SUFFIX = "Aspect" + RESPONSE_SUFFIX;
  private static final String ENTITY_REQUEST_SUFFIX = "Entity" + REQUEST_SUFFIX;
  private static final String ENTITY_RESPONSE_SUFFIX = "Entity" + RESPONSE_SUFFIX;

  public static OpenAPI generateOpenApiSpec(EntityRegistry entityRegistry) {
    final Set<String> aspectNames = entityRegistry.getAspectSpecs().keySet();
    final Set<String> entityNames =
        entityRegistry.getEntitySpecs().values().stream()
            .filter(e -> aspectNames.contains(e.getKeyAspectName()))
            .map(EntitySpec::getName)
            .collect(Collectors.toSet());
    final Set<String> definitionNames =
        Stream.concat(aspectNames.stream(), entityNames.stream()).collect(Collectors.toSet());
    // Info
    final Info info = new Info();
    info.setTitle("Entity API");
    info.setDescription("This is a service for DataHub Entities.");
    info.setVersion("v3");
    // Components
    final Components components = new Components();
    // --> Aspect components
    // TODO: Correct handling of SystemMetadata and SortOrder
    components.addSchemas(
        "SystemMetadata", new Schema().type(TYPE_OBJECT).additionalProperties(true));
    components.addSchemas("SortOrder", new Schema()._enum(List.of("ASCENDING", "DESCENDING")));
    components.addSchemas("AspectPatch", buildAspectPatchSchema());
    entityRegistry
        .getAspectSpecs()
        .values()
        .forEach(
            a -> {
              final String upperAspectName = a.getPegasusSchema().getName();
              addAspectSchemas(components, a);
              components.addSchemas(
                  upperAspectName + ASPECT_REQUEST_SUFFIX,
                  buildAspectRefRequestSchema(upperAspectName));
              components.addSchemas(
                  upperAspectName + ASPECT_RESPONSE_SUFFIX,
                  buildAspectRefResponseSchema(upperAspectName));
            });
    // --> Entity components
    entityRegistry.getEntitySpecs().values().stream()
        .filter(e -> aspectNames.contains(e.getKeyAspectName()))
        .forEach(
            e -> {
              final String entityName = toUpperFirst(e.getName());
              components.addSchemas(
                  entityName + ENTITY_REQUEST_SUFFIX, buildEntitySchema(e, aspectNames, false));
              components.addSchemas(
                  entityName + ENTITY_RESPONSE_SUFFIX, buildEntitySchema(e, aspectNames, true));
              components.addSchemas(
                  "Scroll" + entityName + ENTITY_RESPONSE_SUFFIX, buildEntityScrollSchema(e));
            });
    // Parameters
    entityRegistry.getEntitySpecs().values().stream()
        .filter(e -> definitionNames.contains(e.getKeyAspectName()))
        .forEach(
            e -> {
              final String parameterName = toUpperFirst(e.getName()) + "Aspects";
              components.addParameters(
                  parameterName + MODEL_VERSION, buildParameterSchema(e, definitionNames));
            });
    addExtraParameters(components);
    // Path
    final Paths paths = new Paths();
    entityRegistry.getEntitySpecs().values().stream()
        .filter(e -> definitionNames.contains(e.getName()))
        .forEach(
            e -> {
              paths.addPathItem(
                  String.format("/v3/entity/%s", e.getName().toLowerCase()),
                  buildListEntityPath(e));
              paths.addPathItem(
                  String.format("/v3/entity/%s/{urn}", e.getName().toLowerCase()),
                  buildSingleEntityPath(e));
            });
    entityRegistry.getEntitySpecs().values().stream()
        .filter(e -> definitionNames.contains(e.getName()))
        .forEach(
            e -> {
              e.getAspectSpecs().stream()
                  .filter(a -> definitionNames.contains(a.getName()))
                  .forEach(
                      a ->
                          paths.addPathItem(
                              String.format(
                                  "/v3/entity/%s/{urn}/%s",
                                  e.getName().toLowerCase(), a.getName().toLowerCase()),
                              buildSingleEntityAspectPath(
                                  e, a.getName(), a.getPegasusSchema().getName())));
            });
    return new OpenAPI().openapi("3.0.1").info(info).paths(paths).components(components);
  }

  private static PathItem buildSingleEntityPath(final EntitySpec entity) {
    final String upperFirst = toUpperFirst(entity.getName());
    final String aspectParameterName = upperFirst + "Aspects";
    final PathItem result = new PathItem();

    // Get Operation
    final List<Parameter> parameters =
        List.of(
            new Parameter()
                .in(NAME_PATH)
                .name("urn")
                .description("The entity's unique URN id.")
                .schema(new Schema().type(TYPE_STRING)),
            new Parameter()
                .in(NAME_QUERY)
                .name("systemMetadata")
                .description("Include systemMetadata with response.")
                .schema(new Schema().type(TYPE_BOOLEAN)._default(false)),
            new Parameter()
                .$ref(
                    String.format(
                        "#/components/parameters/%s", aspectParameterName + MODEL_VERSION)));
    final ApiResponse successApiResponse =
        new ApiResponse()
            .description("Success")
            .content(
                new Content()
                    .addMediaType(
                        "application/json",
                        new MediaType()
                            .schema(
                                new Schema()
                                    .$ref(
                                        String.format(
                                            "#/components/schemas/%s%s",
                                            upperFirst, ENTITY_RESPONSE_SUFFIX)))));
    final Operation getOperation =
        new Operation()
            .summary(String.format("Get %s.", upperFirst))
            .parameters(parameters)
            .tags(List.of(entity.getName() + " Entity"))
            .responses(new ApiResponses().addApiResponse("200", successApiResponse));

    // Head Operation
    final ApiResponse successHeadResponse =
        new ApiResponse()
            .description(String.format("%s  exists.", entity.getName()))
            .content(new Content().addMediaType("application/json", new MediaType()));
    final ApiResponse notFoundHeadResponse =
        new ApiResponse()
            .description(String.format("%s does not exist.", entity.getName()))
            .content(new Content().addMediaType("application/json", new MediaType()));
    final Operation headOperation =
        new Operation()
            .summary(String.format("%s existence.", upperFirst))
            .parameters(
                List.of(
                    new Parameter()
                        .in(NAME_PATH)
                        .name("urn")
                        .description("The entity's unique URN id.")
                        .schema(new Schema().type(TYPE_STRING))))
            .tags(List.of(entity.getName() + " Entity"))
            .responses(
                new ApiResponses()
                    .addApiResponse("204", successHeadResponse)
                    .addApiResponse("404", notFoundHeadResponse));
    // Delete Operation
    final ApiResponse successDeleteResponse =
        new ApiResponse()
            .description(String.format("Delete %s entity.", upperFirst))
            .content(new Content().addMediaType("application/json", new MediaType()));
    final Operation deleteOperation =
        new Operation()
            .summary(String.format("Delete entity %s", upperFirst))
            .parameters(
                List.of(
                    new Parameter()
                        .in(NAME_PATH)
                        .name("urn")
                        .description("The entity's unique URN id.")
                        .schema(new Schema().type(TYPE_STRING))))
            .tags(List.of(entity.getName() + " Entity"))
            .responses(new ApiResponses().addApiResponse("200", successDeleteResponse));

    return result.get(getOperation).head(headOperation).delete(deleteOperation);
  }

  private static PathItem buildListEntityPath(final EntitySpec entity) {
    final String upperFirst = toUpperFirst(entity.getName());
    final String aspectParameterName = upperFirst + "Aspects";
    final PathItem result = new PathItem();
    final List<Parameter> parameters =
        List.of(
            new Parameter()
                .in(NAME_QUERY)
                .name("systemMetadata")
                .description("Include systemMetadata with response.")
                .schema(new Schema().type(TYPE_BOOLEAN)._default(false)),
            new Parameter()
                .$ref(
                    String.format(
                        "#/components/parameters/%s", aspectParameterName + MODEL_VERSION)),
            new Parameter().$ref("#/components/parameters/PaginationCount" + MODEL_VERSION),
            new Parameter().$ref("#/components/parameters/ScrollId" + MODEL_VERSION),
            new Parameter().$ref("#/components/parameters/SortBy" + MODEL_VERSION),
            new Parameter().$ref("#/components/parameters/SortOrder" + MODEL_VERSION),
            new Parameter().$ref("#/components/parameters/ScrollQuery" + MODEL_VERSION));
    final ApiResponse successApiResponse =
        new ApiResponse()
            .description("Success")
            .content(
                new Content()
                    .addMediaType(
                        "application/json",
                        new MediaType()
                            .schema(
                                new Schema()
                                    .$ref(
                                        String.format(
                                            "#/components/schemas/Scroll%s%s",
                                            upperFirst, ENTITY_RESPONSE_SUFFIX)))));
    result.setGet(
        new Operation()
            .summary(String.format("Scroll/List %s.", upperFirst))
            .parameters(parameters)
            .tags(List.of(entity.getName() + " Entity"))
            .responses(new ApiResponses().addApiResponse("200", successApiResponse)));

    // Post Operation
    final Content requestCreateContent =
        new Content()
            .addMediaType(
                "application/json",
                new MediaType()
                    .schema(
                        new Schema()
                            .type(TYPE_ARRAY)
                            .items(
                                new Schema()
                                    .$ref(
                                        String.format(
                                            "#/components/schemas/%s%s",
                                            upperFirst, ENTITY_REQUEST_SUFFIX)))));
    final ApiResponse apiCreateResponse =
        new ApiResponse()
            .description("Create a batch of " + entity.getName() + " entities.")
            .content(
                new Content()
                    .addMediaType(
                        "application/json",
                        new MediaType()
                            .schema(
                                new Schema()
                                    .type(TYPE_ARRAY)
                                    .items(
                                        new Schema<>()
                                            .$ref(
                                                String.format(
                                                    "#/components/schemas/%s%s",
                                                    upperFirst, ENTITY_RESPONSE_SUFFIX))))));
    final ApiResponse apiCreateAsyncResponse =
        new ApiResponse()
            .description("Async batch creation of " + entity.getName() + " entities submitted.")
            .content(new Content().addMediaType("application/json", new MediaType()));

    result.setPost(
        new Operation()
            .parameters(
                List.of(
                    new Parameter()
                        .in(NAME_QUERY)
                        .name("async")
                        .description("Use async ingestion for high throughput.")
                        .schema(new Schema().type(TYPE_BOOLEAN)._default(true)),
                    new Parameter()
                        .in(NAME_QUERY)
                        .name(NAME_SYSTEM_METADATA)
                        .description("Include systemMetadata with response.")
                        .schema(new Schema().type(TYPE_BOOLEAN)._default(false))))
            .summary("Create " + upperFirst + " entities.")
            .tags(List.of(entity.getName() + " Entity"))
            .requestBody(
                new RequestBody()
                    .description("Create " + entity.getName() + " entities.")
                    .required(true)
                    .content(requestCreateContent))
            .responses(
                new ApiResponses()
                    .addApiResponse("200", apiCreateResponse)
                    .addApiResponse("202", apiCreateAsyncResponse)));

    return result;
  }

  private static void addExtraParameters(final Components components) {
    components.addParameters(
        "ScrollId" + MODEL_VERSION,
        new Parameter()
            .in(NAME_QUERY)
            .name(NAME_SCROLL_ID)
            .description("Scroll pagination token.")
            .schema(new Schema().type(TYPE_STRING)));
    components.addParameters(
        "SortBy" + MODEL_VERSION,
        new Parameter()
            .in(NAME_QUERY)
            .name("sort")
            .explode(true)
            .description("Sort fields for pagination.")
            .example(PROPERTY_URN)
            .schema(
                new Schema()
                    .type(TYPE_ARRAY)
                    ._default(List.of(PROPERTY_URN))
                    .items(
                        new Schema<>()
                            .type(TYPE_STRING)
                            ._enum(List.of(PROPERTY_URN))
                            ._default(PROPERTY_URN))));
    components.addParameters(
        "SortOrder" + MODEL_VERSION,
        new Parameter()
            .in(NAME_QUERY)
            .name("sortOrder")
            .explode(true)
            .description("Sort direction field for pagination.")
            .example("ASCENDING")
            .schema(new Schema()._default("ASCENDING").$ref("#/components/schemas/SortOrder")));
    components.addParameters(
        "PaginationCount" + MODEL_VERSION,
        new Parameter()
            .in(NAME_QUERY)
            .name("count")
            .description("Number of items per page.")
            .example(10)
            .schema(new Schema().type(TYPE_INTEGER)._default(10).minimum(new BigDecimal(1))));
    components.addParameters(
        "ScrollQuery" + MODEL_VERSION,
        new Parameter()
            .in(NAME_QUERY)
            .name(NAME_QUERY)
            .description("Structured search query.")
            .example("*")
            .schema(new Schema().type(TYPE_STRING)._default("*")));
  }

  private static Parameter buildParameterSchema(
      final EntitySpec entity, final Set<String> definitionNames) {
    final List<String> aspectNames =
        entity.getAspectSpecs().stream()
            .map(AspectSpec::getName)
            .filter(definitionNames::contains) // Only if aspect is defined
            .distinct()
            .collect(Collectors.toList());
    if (aspectNames.isEmpty()) {
      aspectNames.add(entity.getKeyAspectName());
    }
    final Schema schema =
        new Schema()
            .type(TYPE_ARRAY)
            .items(
                new Schema()
                    .type(TYPE_STRING)
                    ._enum(aspectNames)
                    ._default(aspectNames.stream().findFirst().orElse(null)));
    return new Parameter()
        .in(NAME_QUERY)
        .name("aspects")
        .explode(true)
        .description("Aspects to include in response.")
        .example(aspectNames)
        .schema(schema);
  }

  private static void addAspectSchemas(final Components components, final AspectSpec aspect) {
    final org.apache.avro.Schema avroSchema =
        SchemaTranslator.dataToAvroSchema(aspect.getPegasusSchema().getDereferencedDataSchema());
    try {
      final JsonNode apiSchema = ProcessingUtil.buildResult(avroSchema.toString());
      final JsonNode definitions = apiSchema.get("definitions");
      definitions
          .fieldNames()
          .forEachRemaining(
              n -> {
                try {
                  final String definition = Json.mapper().writeValueAsString(definitions.get(n));
                  final String newDefinition =
                      definition.replaceAll("definitions", "components/schemas");
                  Schema s = Json.mapper().readValue(newDefinition, Schema.class);
                  Set<String> requiredNames =
                      Optional.ofNullable(s.getRequired())
                          .map(names -> Set.copyOf(names))
                          .orElse(new HashSet());
                  Map<String, Schema> properties =
                      Optional.ofNullable(s.getProperties()).orElse(new HashMap<>());
                  properties.forEach(
                      (name, schema) -> {
                        String $ref = schema.get$ref();
                        boolean isNameRequired = requiredNames.contains(name);
                        if ($ref != null && !isNameRequired) {
                          // A non-required $ref property must be wrapped in a { allOf: [ $ref ] }
                          // object to allow the
                          // property to be marked as nullable
                          schema.setType(TYPE_OBJECT);
                          schema.set$ref(null);
                          schema.setAllOf(List.of(new Schema().$ref($ref)));
                        }
                        schema.setNullable(!isNameRequired);
                      });

                  components.addSchemas(n, s);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static Schema buildAspectRefResponseSchema(final String aspectName) {
    final Schema result =
        new Schema<>()
            .type(TYPE_OBJECT)
            .description(ASPECT_DESCRIPTION)
            .required(List.of(PROPERTY_VALUE))
            .addProperty(PROPERTY_VALUE, new Schema<>().$ref(PATH_DEFINITIONS + aspectName));
    result.addProperty(
        "systemMetadata",
        new Schema<>()
            .type(TYPE_OBJECT)
            .allOf(List.of(new Schema().$ref(PATH_DEFINITIONS + "SystemMetadata")))
            .description("System metadata for the aspect.")
            .nullable(true));
    return result;
  }

  private static Schema buildAspectRefRequestSchema(final String aspectName) {
    return new Schema<>().$ref(PATH_DEFINITIONS + aspectName);
  }

  private static Schema buildEntitySchema(
      final EntitySpec entity, Set<String> aspectNames, final boolean withSystemMetadata) {
    final Map<String, Schema> properties =
        entity.getAspectSpecMap().entrySet().stream()
            .filter(a -> aspectNames.contains(a.getValue().getName()))
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    a ->
                        buildAspectRef(
                            a.getValue().getPegasusSchema().getName(), withSystemMetadata)));
    properties.put(
        PROPERTY_URN,
        new Schema<>().type(TYPE_STRING).description("Unique id for " + entity.getName()));
    properties.put(
        entity.getKeyAspectName(),
        buildAspectRef(entity.getKeyAspectSpec().getPegasusSchema().getName(), withSystemMetadata));
    return new Schema<>()
        .type(TYPE_OBJECT)
        .description(toUpperFirst(entity.getName()) + " object.")
        .required(List.of(PROPERTY_URN))
        .properties(properties);
  }

  private static Schema buildEntityScrollSchema(final EntitySpec entity) {
    return new Schema<>()
        .type(TYPE_OBJECT)
        .description("Scroll across (list) " + toUpperFirst(entity.getName()) + " objects.")
        .required(List.of("entities"))
        .addProperty(
            NAME_SCROLL_ID,
            new Schema<>().type(TYPE_STRING).description("Scroll id for pagination."))
        .addProperty(
            "entities",
            new Schema<>()
                .type(TYPE_ARRAY)
                .description(toUpperFirst(entity.getName()) + " object.")
                .items(
                    new Schema<>()
                        .$ref(
                            String.format(
                                "#/components/schemas/%s%s",
                                toUpperFirst(entity.getName()), ENTITY_RESPONSE_SUFFIX))));
  }

  private static Schema buildAspectRef(final String aspect, final boolean withSystemMetadata) {
    final Schema result = new Schema<>();
    if (withSystemMetadata) {
      result.set$ref(
          String.format(FORMAT_PATH_DEFINITIONS, toUpperFirst(aspect), ASPECT_RESPONSE_SUFFIX));
    } else {
      result.set$ref(
          String.format(FORMAT_PATH_DEFINITIONS, toUpperFirst(aspect), ASPECT_REQUEST_SUFFIX));
    }
    return result;
  }

  private static Schema buildAspectPatchSchema() {
    Map<String, Schema> properties =
        ImmutableMap.<String, Schema>builder()
            .put(
                PROPERTY_PATCH,
                new Schema<>()
                    .type(TYPE_ARRAY)
                    .items(
                        new Schema<>()
                            .type(TYPE_OBJECT)
                            .required(List.of("op", "path"))
                            .properties(
                                Map.of(
                                    "op", new Schema<>().type(TYPE_STRING),
                                    "path", new Schema<>().type(TYPE_STRING),
                                    "value", new Schema<>().type(TYPE_OBJECT)))))
            .put(PROPERTY_PATCH_PKEY, new Schema<>().type(TYPE_OBJECT))
            .build();

    return new Schema<>()
        .type(TYPE_OBJECT)
        .description(
            "Extended JSON Patch to allow for manipulating array sets which represent maps where each element has a unique primary key.")
        .required(List.of(PROPERTY_PATCH))
        .properties(properties);
  }

  private static PathItem buildSingleEntityAspectPath(
      final EntitySpec entity, final String aspect, final String upperFirstAspect) {
    final String upperFirstEntity = toUpperFirst(entity.getName());

    List<String> tags = List.of(aspect + " Aspect");
    // Get Operation
    final Parameter getParameter =
        new Parameter()
            .in(NAME_QUERY)
            .name(NAME_SYSTEM_METADATA)
            .description("Include systemMetadata with response.")
            .schema(new Schema().type(TYPE_BOOLEAN)._default(false));
    final ApiResponse successApiResponse =
        new ApiResponse()
            .description("Success")
            .content(
                new Content()
                    .addMediaType(
                        "application/json",
                        new MediaType()
                            .schema(
                                new Schema()
                                    .$ref(
                                        String.format(
                                            "#/components/schemas/%s%s",
                                            upperFirstAspect, ASPECT_RESPONSE_SUFFIX)))));
    final Operation getOperation =
        new Operation()
            .summary(String.format("Get %s for %s.", aspect, entity.getName()))
            .tags(tags)
            .parameters(List.of(getParameter))
            .responses(new ApiResponses().addApiResponse("200", successApiResponse));
    // Head Operation
    final ApiResponse successHeadResponse =
        new ApiResponse()
            .description(String.format("%s on %s exists.", aspect, entity.getName()))
            .content(new Content().addMediaType("application/json", new MediaType()));
    final ApiResponse notFoundHeadResponse =
        new ApiResponse()
            .description(String.format("%s on %s does not exist.", aspect, entity.getName()))
            .content(new Content().addMediaType("application/json", new MediaType()));
    final Operation headOperation =
        new Operation()
            .summary(String.format("%s on %s existence.", aspect, upperFirstEntity))
            .tags(tags)
            .responses(
                new ApiResponses()
                    .addApiResponse("200", successHeadResponse)
                    .addApiResponse("404", notFoundHeadResponse));
    // Delete Operation
    final ApiResponse successDeleteResponse =
        new ApiResponse()
            .description(String.format("Delete %s on %s entity.", aspect, upperFirstEntity))
            .content(new Content().addMediaType("application/json", new MediaType()));
    final Operation deleteOperation =
        new Operation()
            .summary(String.format("Delete %s on entity %s", aspect, upperFirstEntity))
            .tags(tags)
            .responses(new ApiResponses().addApiResponse("200", successDeleteResponse));
    // Post Operation
    final ApiResponse successPostResponse =
        new ApiResponse()
            .description(String.format("Create aspect %s on %s entity.", aspect, upperFirstEntity))
            .content(
                new Content()
                    .addMediaType(
                        "application/json",
                        new MediaType()
                            .schema(
                                new Schema()
                                    .$ref(
                                        String.format(
                                            "#/components/schemas/%s%s",
                                            upperFirstAspect, ASPECT_RESPONSE_SUFFIX)))));
    final RequestBody requestBody =
        new RequestBody()
            .description(String.format("Create aspect %s on %s entity.", aspect, upperFirstEntity))
            .required(true)
            .content(
                new Content()
                    .addMediaType(
                        "application/json",
                        new MediaType()
                            .schema(
                                new Schema()
                                    .$ref(
                                        String.format(
                                            "#/components/schemas/%s%s",
                                            upperFirstAspect, ASPECT_REQUEST_SUFFIX)))));
    final Operation postOperation =
        new Operation()
            .summary(String.format("Create aspect %s on %s ", aspect, upperFirstEntity))
            .tags(tags)
            .requestBody(requestBody)
            .responses(new ApiResponses().addApiResponse("201", successPostResponse));
    // Patch Operation
    final ApiResponse successPatchResponse =
        new ApiResponse()
            .description(String.format("Patch aspect %s on %s entity.", aspect, upperFirstEntity))
            .content(
                new Content()
                    .addMediaType(
                        "application/json",
                        new MediaType()
                            .schema(
                                new Schema()
                                    .$ref(
                                        String.format(
                                            "#/components/schemas/%s%s",
                                            upperFirstAspect, ASPECT_RESPONSE_SUFFIX)))));
    final RequestBody patchRequestBody =
        new RequestBody()
            .description(String.format("Patch aspect %s on %s entity.", aspect, upperFirstEntity))
            .required(true)
            .content(
                new Content()
                    .addMediaType(
                        "application/json",
                        new MediaType()
                            .schema(new Schema().$ref("#/components/schemas/AspectPatch"))));
    final Operation patchOperation =
        new Operation()
            .parameters(
                List.of(
                    new Parameter()
                        .in(NAME_QUERY)
                        .name("systemMetadata")
                        .description("Include systemMetadata with response.")
                        .schema(new Schema().type(TYPE_BOOLEAN)._default(false))))
            .summary(String.format("Patch aspect %s on %s ", aspect, upperFirstEntity))
            .tags(tags)
            .requestBody(patchRequestBody)
            .responses(new ApiResponses().addApiResponse("200", successPatchResponse));
    return new PathItem()
        .parameters(
            List.of(
                new Parameter()
                    .in("path")
                    .name("urn")
                    .required(true)
                    .schema(new Schema().type(TYPE_STRING))))
        .get(getOperation)
        .head(headOperation)
        .delete(deleteOperation)
        .post(postOperation)
        .patch(patchOperation);
  }
}
