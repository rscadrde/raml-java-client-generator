package org.mule.client.codegen;

import com.fasterxml.jackson.core.JsonParseException;
import com.sun.codemodel.*;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jsonschema2pojo.*;
import org.jsonschema2pojo.rules.RuleFactory;
import org.mule.client.codegen.clientgenerator.Jersey2RestClientGeneratorImpl;
import org.mule.client.codegen.model.JBodyType;
import org.mule.client.codegen.security.BasicAuthClientGenerator;
import org.mule.client.codegen.security.NoSecuredClientGenerator;
import org.mule.client.codegen.security.SecurityClientGenerator;
import org.mule.client.codegen.utils.MimeTypeHelper;
import org.mule.client.codegen.utils.NameHelper;
import org.mule.client.codegen.utils.SecuritySchemesHelper;
import org.mule.client.codegen.utils.TypeConstants;
import org.mule.raml.ApiModelLoader;
import org.mule.raml.model.*;

import javax.ws.rs.client.Client;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;

import static org.mule.client.codegen.utils.SecuritySchemesHelper.BASIC_AUTHENTICATION;

public class RamlJavaClientGenerator {

    public static final String OK_RESPONSE = "200";

    // Code generation constants
    public static final String RESPONSE_CLASS_SUFFIX = "Response";
    public static final String HEADER_CLASS_SUFFIX = "Header";
    public static final String QUERY_PARAM_CLASS_SUFFIX = "QueryParam";
    public static final String BODY_CLASS_SUFFIX = "Body";
    public static final String CLIENT_CLASS_SUFFIX = "Client";

    public static final String PRIVATE_FIELD_PREFIX = "_";

    public static final String PACKAGE_SEPARATOR = ".";
    public static final String MODEL_PACKAGE_NAME = "model";
    public static final String BASE_URL_FIELD_NAME = "baseUrl";
    public static final String URI_PARAM_FIELD_NAME = "uriParam";
    public static final String GET_BASE_URI_METHOD_NAME = "getBaseUri";
    public static final String CLIENT_FIELD_NAME = "_client";

    // Types
    private static Map<String, Class<?>> CLASS_BY_TYPE = new HashMap<>();

    {
        CLASS_BY_TYPE.put(TypeConstants.STRING, String.class);
        CLASS_BY_TYPE.put(TypeConstants.NUMBER, Double.class);
        CLASS_BY_TYPE.put(TypeConstants.INTEGER, Integer.class);
        CLASS_BY_TYPE.put(TypeConstants.DATE, Date.class);
        CLASS_BY_TYPE.put(TypeConstants.FILE, File.class);
        CLASS_BY_TYPE.put(TypeConstants.BOOLEAN, Boolean.class);
    }

    private String basePackage;
    private File targetFolder;
    private RestClientGenerator clientGenerator;

    // This two properties hold state so maybe should be local and pass through
    private Map<String, JType> globalTypes;
    private Map<String, Pair<JDefinedClass, JMethod>> resourceClasses;

    public RamlJavaClientGenerator(String basePackage, File targetFolder) {
        this.basePackage = basePackage;
        this.targetFolder = targetFolder;
        this.clientGenerator = new Jersey2RestClientGeneratorImpl();
        this.resourceClasses = new HashMap<>();
        this.globalTypes = new HashMap<>();
    }

    public void generate(URL ramlFile) throws JClassAlreadyExistsException, IOException {
        if (ramlFile == null) {
            throw new IllegalArgumentException("Url can not be null ");
        }
        globalTypes.clear();
        resourceClasses.clear();
        System.out.println("Start generating for " + ramlFile);
        try (final InputStreamReader inputStreamReader = new InputStreamReader(ramlFile.openStream())) {
            final ApiModel raml = ApiModelLoader.build(inputStreamReader, ramlFile.toExternalForm());
            System.out.println("Parsed successfully " + ramlFile);
            generate(raml);
        }

    }

    private void generate(ApiModel raml) throws JClassAlreadyExistsException, IOException {
        final Map<String, Resource> resources = raml.getResources();
        final JCodeModel cm = new JCodeModel();
        final List<Pair<String, SecurityScheme>> supportedSecuritySchemes = SecuritySchemesHelper.getSupportedSecuritySchemes(raml);
        final List<Map<String, String>> schemas = raml.getSchemas();
        final List<JFieldVar> generatedRequiredField = new ArrayList<>();
        SecurityClientGenerator clientGenerator = new NoSecuredClientGenerator();

        for (Map<String, String> schema : schemas) {
            for (Map.Entry<String, String> schemaEntry : schema.entrySet()) {
                String packageName = basePackage + PACKAGE_SEPARATOR + MODEL_PACKAGE_NAME;
                String value = schemaEntry.getValue();
                final JType schemaType = generatePojoFromSchema(cm, NameHelper.toValidClassName(schemaEntry.getKey()), packageName, value, SourceType.JSONSCHEMA);
                globalTypes.put(value, schemaType);
            }
        }

        this.clientGenerator.buildCustomException(cm, basePackage, raml.getTitle());

        final JDefinedClass containerClass = cm
                ._class(basePackage + PACKAGE_SEPARATOR + "api" + PACKAGE_SEPARATOR + NameHelper.toValidClassName(raml.getTitle()) + CLIENT_CLASS_SUFFIX);

        //Check if exist a supported security exist
        if (!supportedSecuritySchemes.isEmpty()) {
            Pair<String, SecurityScheme> stringSecuritySchemePair = supportedSecuritySchemes.get(0);
            switch (stringSecuritySchemePair.getValue().getType()) {
                case BASIC_AUTHENTICATION: {
                    generatedRequiredField.addAll(SecuritySchemesHelper.createBasicAuthFields(containerClass));
                    clientGenerator = new BasicAuthClientGenerator(generatedRequiredField);
                    break;
                }
            }
        }

        final JFieldVar baseUriField = containerClass.field(JMod.PRIVATE, String.class, "_" + BASE_URL_FIELD_NAME);

        JMethod getClientMethod = clientGenerator.createClient(containerClass);

        final JMethod containerConstructor = containerClass.constructor(JMod.PUBLIC);
        final JVar baseUriParam = containerConstructor.param(String.class, BASE_URL_FIELD_NAME);

        for (JFieldVar jFieldVar : generatedRequiredField) {
            JVar param = containerConstructor.param(jFieldVar.type(), jFieldVar.name());
            containerConstructor.body().assign(JExpr._this().ref(jFieldVar), param);
        }

        containerConstructor.body().assign(baseUriField, baseUriParam);

        containerClass.method(JMod.PROTECTED, String.class, GET_BASE_URI_METHOD_NAME).body()._return(baseUriField);

        final JMethod factoryMethod = containerClass.method(JMod.PUBLIC | JMod.STATIC, containerClass, "create");
        final JVar baseUrlFactoryParam = factoryMethod.param(cm.ref(String.class), BASE_URL_FIELD_NAME);

        for (JFieldVar field : generatedRequiredField) {
            factoryMethod.param(field.type(), field.name());
        }

        JInvocation invocation = JExpr._new(containerClass).arg(baseUrlFactoryParam);

        for (JVar generatedMethodRequiredVar : generatedRequiredField) {
            invocation.arg(generatedMethodRequiredVar);
        }

        factoryMethod.body()._return(invocation);

        if (raml.getDocumentation() != null && !raml.getDocumentation().isEmpty()) {
            containerClass.javadoc().add(raml.getDocumentation().get(0).getContent());
        }

        if (StringUtils.isNotBlank(raml.getBaseUri())) {
            JMethod constructor = containerClass.constructor(JMod.PUBLIC);
            JInvocation thisInvocation = JExpr.invoke("this");
            thisInvocation.arg(JExpr.lit(raml.getBaseUri().replaceAll("/$", "")));
            for (JVar var : generatedRequiredField) {
                JVar param = constructor.param(var.type(), var.name());
                thisInvocation.arg(param);
            }
            constructor.body().add(thisInvocation);

            JMethod create = containerClass.method(JMod.PUBLIC | JMod.STATIC, containerClass, "create");
            JInvocation newInvocation = JExpr._new(containerClass);

            for (JFieldVar var : generatedRequiredField) {
                JVar param = create.param(var.type(), var.name());
                newInvocation.arg(param);
            }
            create.body()._return(newInvocation);

        }

        buildResourceClass(cm, containerClass, containerConstructor, resources, "", getClientMethod, raml);

        if (!targetFolder.exists()) {
            targetFolder.mkdirs();
        }

        cm.build(targetFolder);
        System.out.println("Finished Generation");
    }

    private void buildResourceClass(JCodeModel cm, JDefinedClass containerClass, JMethod containerConstructor, Map<String, Resource> resources, String containerResource,
                                    JMethod getClientMethod, ApiModel apiModel) throws JClassAlreadyExistsException, IOException {

        for (Map.Entry<String, Resource> stringResourceEntry : resources.entrySet()) {
            JDefinedClass parentClass = containerClass;
            JMethod parentConstructor = containerConstructor;
            String parentResource = containerResource;
            final String resourcesName = stringResourceEntry.getKey();
            final String[] resourceParts = resourcesName.split("/");
            if (resourceParts.length > 1) {
                for (int i = 1; i < resourceParts.length; i++) {
                    final String resourceName = resourceParts[i];
                    final Resource resource = stringResourceEntry.getValue();
                    final String resourcePath = parentResource + "/" + resourceName;
                    System.out.println("-" + resourcePath);
                    final JDefinedClass resourceClass;
                    final JMethod resourceConstructor;
                    //Check if it is not already defined

                    if (!resourceClasses.containsKey(resourcePath)) {
                        final String resourceDescription = resource.getDescription();
                        //Generate resource class
                        resourceClass = cm._class(getResourcePackage(resourcePath) + PACKAGE_SEPARATOR + NameHelper.toValidClassName(resourceName));
                        if (StringUtils.isNotEmpty(resourceDescription)) {
                            resourceClass.javadoc().add(resourceDescription);
                        }

                        final JMethod getClient = resourceClass.method(JMod.PROTECTED, Client.class, "getClient");
                        getClient.body()._return(JExpr._this().ref(CLIENT_FIELD_NAME));

                        final JFieldVar baseUrlField = resourceClass.field(JMod.PRIVATE, String.class, PRIVATE_FIELD_PREFIX + BASE_URL_FIELD_NAME);
                        final JFieldVar clientField = resourceClass.field(JMod.PRIVATE, Client.class, CLIENT_FIELD_NAME);
                        resourceConstructor = resourceClass.constructor(JMod.PUBLIC);
                        final JVar baseUrlParam = resourceConstructor.param(String.class, BASE_URL_FIELD_NAME);
                        final JVar clientParam = resourceConstructor.param(Client.class, CLIENT_FIELD_NAME);
                        final JMethod getResourceMethod = resourceClass.method(JMod.PRIVATE, String.class, GET_BASE_URI_METHOD_NAME);
                        if (isURIParameter(resourceName)) {
                            //Add constructor additional parameter for uriParam
                            final JVar uriParamConstructorParam = resourceConstructor.param(String.class, URI_PARAM_FIELD_NAME);

                            //Link with parent as method
                            final String uriParameterName = resourceName.substring(1, resourceName.length() - 1);
                            final JMethod resourceFactoryMethod = parentClass.method(JMod.PUBLIC | JMod.FINAL, resourceClass, NameHelper.toValidFieldName(uriParameterName));
                            if (StringUtils.isNotEmpty(resourceDescription)) {
                                resourceFactoryMethod.javadoc().add(resourceDescription);
                            }
                            final JVar uriParam = resourceFactoryMethod.param(String.class, NameHelper.toValidFieldName(uriParameterName));
                            resourceFactoryMethod.body()._return(JExpr._new(resourceClass).arg(JExpr.invoke(GET_BASE_URI_METHOD_NAME)).arg(JExpr.invoke(getClient)).arg(uriParam));
                            resourceConstructor.body().assign(baseUrlField, baseUrlParam.plus(JExpr.lit("/").plus(uriParamConstructorParam)));
                            resourceConstructor.body().assign(JExpr._this().ref(clientField), clientParam);

                        } else {
                            resourceConstructor.body().assign(baseUrlField, baseUrlParam.plus(JExpr.lit("/" + resourceName)));
                            resourceConstructor.body().assign(JExpr._this().ref(clientField), clientParam);
                            //Link with parent as field
                            final JFieldVar resourceField = parentClass.field(JMod.PUBLIC | JMod.FINAL, resourceClass, NameHelper.toValidFieldName(resourceName));
                            if (StringUtils.isNotEmpty(resourceDescription)) {
                                resourceField.javadoc().add(resourceDescription);
                            }

                            parentConstructor.body()
                                    .assign(resourceField, JExpr._new(resourceClass).arg(JExpr.invoke(GET_BASE_URI_METHOD_NAME)).arg(JExpr.invoke(getClientMethod)));
                        }

                        clientGenerator.resolveBaseURI(cm, getResourceMethod, baseUrlField);
                        //this.clientGenerator.createClient(cm, resourceClass, baseURIMethod);
                        this.resourceClasses.put(resourcePath, new ImmutablePair<>(resourceClass, resourceConstructor));
                    } else {
                        final Pair<JDefinedClass, JMethod> resourcePair = this.resourceClasses.get(resourcePath);
                        resourceClass = resourcePair.getLeft();
                        resourceConstructor = resourcePair.getRight();
                    }

                    parentClass = resourceClass;
                    parentConstructor = resourceConstructor;
                    parentResource = resourcePath;

                    //Only last resource should trigger children and actions
                    if (i == resourceParts.length - 1) {
                        buildActionMethods(cm, resourceClass, resource, resourcePath, resourceName, apiModel);
                        buildResourceClass(cm, resourceClass, resourceConstructor, resource.getResources(), resourcePath, getClientMethod, apiModel);
                    }
                }
            }
        }
    }

    private String getResourcePackage(String resourcePath) {
        return basePackage + PACKAGE_SEPARATOR + "resource" + PACKAGE_SEPARATOR + NameHelper.toResourcePathToPackageName(resourcePath);
    }

    private boolean isURIParameter(String resourceName) {
        return resourceName.startsWith("{") && resourceName.endsWith("}");
    }

    private void buildActionMethods(JCodeModel cm, JDefinedClass resourceClass, Resource resource, String resourcePath, String resourceName, ApiModel apiModel)
            throws IOException, JClassAlreadyExistsException {
        final Map<ActionType, Action> actions = resource.getActions();
        for (Map.Entry<ActionType, Action> actionTypeActionEntry : actions.entrySet()) {
            final ActionType actionType = actionTypeActionEntry.getKey();
            final Action action = actionTypeActionEntry.getValue();
            System.out.println("  " + action.getType() + "");

            final Response response = action.getResponses().get(OK_RESPONSE);
            final List<JBodyType> bodiesType = buildBodyType(cm, actionType, action, resourcePath, resourceName);
            final JType returnType = buildReturnType(cm, actionType, response, resourcePath, resourceName);
            final JType queryParameterType = buildQueryParametersType(cm, actionType, action, resourcePath, resourceName);
            final JType headerParameterType = buildHeaderType(cm, resourcePath, resourceName, actionType, action);
            if (bodiesType.isEmpty()) {
                clientGenerator.callHttpMethod(cm, resourceClass, returnType, null, queryParameterType, headerParameterType, action, apiModel);
            } else {
                for (JBodyType bodyType : bodiesType) {
                    clientGenerator.callHttpMethod(cm, resourceClass, returnType, bodyType, queryParameterType, headerParameterType, action, apiModel);
                }
            }
        }
    }

    private JType buildReturnType(JCodeModel cm, ActionType actionType, Response response, String resourcePath, String resourceName) throws IOException {
        JType returnType = cm.VOID;
        if (response != null) {
            returnType = cm.ref(String.class);
            if (response.getBody() != null) {
                final Iterator<Map.Entry<String, MimeType>> bodies = response.getBody().entrySet().iterator();
                if (bodies.hasNext()) {
                    final Map.Entry<String, MimeType> bodyEntry = bodies.next();
                    final MimeType mimeType = bodyEntry.getValue();
                    if (MimeTypeHelper.isJsonType(mimeType)) {
                        final String className = NameHelper.toValidClassName(resourceName) + NameHelper.toCamelCase(actionType.name(), false) + RESPONSE_CLASS_SUFFIX;
                        returnType = getOrGeneratePojoFromJsonSchema(cm, resourcePath, mimeType, className);
                        if (returnType == null) {
                            returnType = cm.ref(String.class);
                        }
                    } else if (MimeTypeHelper.isTextType(mimeType)) {
                        returnType = cm.ref(String.class);
                    } else if (MimeTypeHelper.isBinaryType(mimeType)) {
                        returnType = cm.ref(InputStream.class);
                    } else {
                        returnType = cm.ref(String.class);
                    }

                }
            }
        }
        return returnType;
    }

    private JType buildHeaderType(JCodeModel cm, String resourcePath, String resourceName, ActionType actionType, Action action) throws JClassAlreadyExistsException {
        final Map<String, TypeFieldDefinition> headers = action.getHeaders();
        JType headerParameterType = null;
        if (headers != null && !headers.isEmpty()) {
            final String className = NameHelper.toValidClassName(resourceName) + NameHelper.toCamelCase(actionType.name(), false) + HEADER_CLASS_SUFFIX;
            headerParameterType = toParametersJavaBean(cm, className, headers, resourcePath);
        }
        return headerParameterType;
    }

    private JType buildQueryParametersType(JCodeModel cm, ActionType actionType, Action action, String resourcePath, String resourceName) throws JClassAlreadyExistsException {
        final Map<String, TypeFieldDefinition> queryParameters = action.getQueryParameters();
        JType queryParameterType = null;
        if (queryParameters != null && !queryParameters.isEmpty()) {
            final String className = NameHelper.toValidClassName(resourceName) + NameHelper.toCamelCase(actionType.name(), false) + QUERY_PARAM_CLASS_SUFFIX;
            queryParameterType = toParametersJavaBean(cm, className, queryParameters, resourcePath);
        }
        return queryParameterType;
    }

    private List<JBodyType> buildBodyType(JCodeModel cm, ActionType actionType, Action action, String resourcePath, String resourceName)
            throws IOException, JClassAlreadyExistsException {
        final List<JBodyType> result = new ArrayList<>();

        if (action.getBody() != null) {
            for (MimeType mimeType : action.getBody().values()) {
                JType bodyType = null;
                final MimeType body = mimeType;
                final String className = NameHelper.toValidClassName(resourceName) + NameHelper.toCamelCase(actionType.name(), false) + BODY_CLASS_SUFFIX;
                if (MimeTypeHelper.isJsonType(body)) {
                    bodyType = getOrGeneratePojoFromJsonSchema(cm, resourcePath, body, className);
                } else if (MimeTypeHelper.isTextType(body)) {
                    bodyType = cm.ref(String.class);
                } else if (MimeTypeHelper.isBinaryType(body)) {
                    bodyType = cm.ref(InputStream.class);
                } else if (MimeTypeHelper.isMultiPartType(body) || MimeTypeHelper.isFormUrlEncodedType(body)) {
                    final Map<String, TypeFieldDefinition> formParameters = body.getFormParameters();
                    bodyType = toParametersJavaBean(cm, className, formParameters, resourcePath);
                }
                result.add(new JBodyType(bodyType, mimeType));
            }
        }
        return result;
    }

    private JType getOrGeneratePojoFromJsonSchema(JCodeModel cm, String resourcePath, MimeType mimeType, String className) throws IOException {
        JType type = null;

        if (StringUtils.isNotBlank(mimeType.getSchema())) {
            if (globalTypes.containsKey(mimeType.getSchema())) {
                type = globalTypes.get(mimeType.getSchema());
            } else {
                type = generatePojoFromSchema(cm, className, getModelPackage(resourcePath), mimeType.getSchema(), SourceType.JSONSCHEMA);
            }
        } else if (StringUtils.isNotBlank(mimeType.getExample())) {
            type = generatePojoFromSchema(cm, className, getModelPackage(resourcePath), mimeType.getExample(), SourceType.JSON);
        }

        if (type != null && type.fullName().equals("java.lang.Object") && StringUtils.isNotBlank(mimeType.getExample())) {
            type = generatePojoFromSchema(cm, className, getModelPackage(resourcePath), mimeType.getExample(), SourceType.JSON);
        }

        return type;
    }

    private String getModelPackage(String resourcePath) {
        return getResourcePackage(resourcePath) + PACKAGE_SEPARATOR + MODEL_PACKAGE_NAME;
    }

    public JType toParametersJavaBean(JCodeModel cm, String className, Map<String, TypeFieldDefinition> paramMap, String resourcePath) throws JClassAlreadyExistsException {
        final JDefinedClass paramsClass = cm._class(getModelPackage(resourcePath) + PACKAGE_SEPARATOR + className);
        final JMethod paramsConstructor = paramsClass.constructor(JMod.PUBLIC);

        for (Map.Entry<String, TypeFieldDefinition> parameterEntry : paramMap.entrySet()) {
            String parameterName = parameterEntry.getKey();
            TypeFieldDefinition typeFieldDefinition = parameterEntry.getValue();
            String type = typeFieldDefinition.getType();
            String defaultValueDefinition = typeFieldDefinition.getDefaultValue();
            String descriptionDefinition = typeFieldDefinition.getDescription();

            Class<?> fieldType = CLASS_BY_TYPE.get(typeFieldDefinition.getType());
            fieldType = fieldType != null ? fieldType : String.class;

            final JFieldVar paramField;

            if (StringUtils.isNotBlank(defaultValueDefinition)) {
                final JExpression defaultValue;
                switch (typeFieldDefinition.getType()) {
                    case TypeConstants.STRING:
                        defaultValue = JExpr.lit(defaultValueDefinition);
                        break;
                    case TypeConstants.NUMBER:
                        defaultValue = JExpr.lit(Double.parseDouble(defaultValueDefinition));
                        break;
                    case TypeConstants.INTEGER:
                        defaultValue = JExpr.lit(Integer.parseInt(defaultValueDefinition));
                        break;
                    case TypeConstants.DATE:
                        // TODO What to do here?
                        defaultValue = JExpr._null();
                        break;
                    case TypeConstants.FILE:
                        // TODO What to do here?
                        defaultValue = JExpr._null();
                        break;
                    case TypeConstants.BOOLEAN:
                        // TODO What to do here?
                        defaultValue = JExpr.lit(Boolean.parseBoolean(defaultValueDefinition));
                        break;
                    default:
                        defaultValue = JExpr._null();
                        break;
                }
                paramField = paramsClass.field(JMod.PRIVATE, fieldType, PRIVATE_FIELD_PREFIX + NameHelper.toValidFieldName(parameterName), defaultValue);
            } else {
                paramField = paramsClass.field(JMod.PRIVATE, fieldType, PRIVATE_FIELD_PREFIX + NameHelper.toValidFieldName(parameterName));
            }

            if (StringUtils.isNotBlank(descriptionDefinition)) {
                paramField.javadoc().add(descriptionDefinition);
            }
            if (typeFieldDefinition.isRequired()) {
                // In constructor
                final JVar paramParam = paramsConstructor.param(fieldType, NameHelper.toValidFieldName(parameterName));
                paramsConstructor.body().assign(paramField, paramParam);
                if (StringUtils.isNotBlank(descriptionDefinition)) {
                    paramsConstructor.javadoc().addParam(paramParam).append(descriptionDefinition);
                }
            } else {
                // Setter
                final JMethod builderMethod = paramsClass.method(JMod.PUBLIC, paramsClass, NameHelper.getWithName(parameterName));
                final JVar paramParam = builderMethod.param(fieldType, NameHelper.toValidFieldName(parameterName));
                if (StringUtils.isNotBlank(descriptionDefinition)) {
                    builderMethod.javadoc().addParam(paramParam).append(descriptionDefinition);
                }
                builderMethod.body().assign(paramField, paramParam);
                builderMethod.body()._return(JExpr._this());
            }

            // Setter
            final JMethod setterMethod = paramsClass.method(JMod.PUBLIC, cm.VOID, NameHelper.getSetterName(parameterName));
            final JVar paramParam = setterMethod.param(fieldType, NameHelper.toValidFieldName(parameterName));
            setterMethod.body().assign(paramField, paramParam);

            // Getter
            final JMethod getterMethod = paramsClass.method(JMod.PUBLIC, fieldType, NameHelper.getGetterName(parameterName));
            getterMethod.body()._return(paramField);
            if (StringUtils.isNotBlank(descriptionDefinition)) {
                getterMethod.javadoc().addReturn().append(descriptionDefinition);
            }
        }

        return paramsClass;
    }

    public JType generatePojoFromSchema(JCodeModel codeModel, String className, String packageName, String json, SourceType sourceType) throws IOException {
        try {
            SchemaMapper schemaMapper = new SchemaMapper(getRuleFactory(sourceType), new SchemaGenerator());
            return schemaMapper.generate(codeModel, className, packageName, json);
        } catch (JsonParseException e) {
            System.out.println("Can not generate  " + className + " from schema since : " + e.getMessage());
            return codeModel.ref(String.class);
        }
    }


    private RuleFactory getRuleFactory(final SourceType sourceType) {
        final DefaultGenerationConfig generationConfig = new JsonSchemaGeneratorConfiguration(sourceType);

        return new RuleFactory(generationConfig, new Jackson2Annotator(generationConfig), new SchemaStore());
    }

    private static class JsonSchemaGeneratorConfiguration extends DefaultGenerationConfig {
        private SourceType sourceType;

        public JsonSchemaGeneratorConfiguration(SourceType sourceType) {
            this.sourceType = sourceType;
        }

        @Override
        public SourceType getSourceType() {
            return this.sourceType;
        }

        @Override
        public boolean isUseLongIntegers() {
            return true;
        }

        @Override
        public boolean isIncludeConstructors() {
            return true;
        }

        @Override
        public boolean isConstructorsRequiredPropertiesOnly() {
            return false;
        }

        @Override
        public boolean isGenerateBuilders() {
            return true;
        }
    }
}
