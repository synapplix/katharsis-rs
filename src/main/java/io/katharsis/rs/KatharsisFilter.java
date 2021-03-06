package io.katharsis.rs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.katharsis.dispatcher.RequestDispatcher;
import io.katharsis.errorhandling.exception.KatharsisException;
import io.katharsis.errorhandling.mapper.def.KatharsisExceptionMapper;
import io.katharsis.queryParams.RequestParams;
import io.katharsis.queryParams.RequestParamsBuilder;
import io.katharsis.request.dto.RequestBody;
import io.katharsis.request.path.JsonPath;
import io.katharsis.request.path.PathBuilder;
import io.katharsis.resource.registry.ResourceRegistry;
import io.katharsis.response.BaseResponse;
import io.katharsis.rs.type.JsonApiMediaType;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import static io.katharsis.rs.type.JsonApiMediaType.APPLICATION_JSON_API_TYPE;

/**
 * Handles JSON API requests.
 * <p>
 * Consumes: <i>null</i> | {@link JsonApiMediaType}
 * Produces: {@link JsonApiMediaType}
 * </p>
 * <p>
 * Currently the response is sent using {@link ContainerRequestContext#abortWith(Response)} which might cause
 * problems with Jackson, co the serialization is happening in this filter.
 * </p>
 * <p>
 * To be able to send a request to Katharsis it is necessary to provide full media type alongside the request.
 * Wildcards are not accepted.
 * </p>
 */
@PreMatching
public class KatharsisFilter implements ContainerRequestFilter {

    private ObjectMapper objectMapper;
    private ResourceRegistry resourceRegistry;
    private RequestDispatcher requestDispatcher;

    public KatharsisFilter(ObjectMapper objectMapper, ResourceRegistry resourceRegistry, RequestDispatcher
            requestDispatcher) {
        this.objectMapper = objectMapper;
        this.resourceRegistry = resourceRegistry;
        this.requestDispatcher = requestDispatcher;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (isAcceptableMediaType(requestContext) && isAcceptableContentType(requestContext)) {
            try {
                dispatchRequest(requestContext);
            } catch (Exception e) {
                throw new WebApplicationException(e);
            }
        }
    }

    private void dispatchRequest(ContainerRequestContext requestContext) throws Exception {
        UriInfo uriInfo = requestContext.getUriInfo();
        BaseResponse<?> katharsisResponse = null;
        //TODO: Refactor
        try {
            JsonPath jsonPath = new PathBuilder(resourceRegistry).buildPath(uriInfo.getPath());

            RequestParams requestParams = createRequestParams(uriInfo);

            String method = requestContext.getMethod();
            RequestBody requestBody = inputStreamToBody(requestContext.getEntityStream());

            katharsisResponse = requestDispatcher
                    .dispatchRequest(jsonPath, method, requestParams, requestBody);
        } catch (KatharsisException e) {
            katharsisResponse = new KatharsisExceptionMapper().toErrorResponse(e);
        } finally {
            Response response;
            if (katharsisResponse != null) {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                objectMapper.writeValue(os, katharsisResponse);
                response = Response
                        .status(katharsisResponse.getHttpStatus())
                        .entity(new ByteArrayInputStream(os.toByteArray()))
                        .type(APPLICATION_JSON_API_TYPE)
                        .build();
            } else {
                response = Response.noContent().build();
            }
            requestContext.abortWith(response);
        }
    }

    private boolean isAcceptableMediaType(ContainerRequestContext requestContext) {
        boolean result = false;
        for (MediaType acceptableType : requestContext.getAcceptableMediaTypes()) {
            if (APPLICATION_JSON_API_TYPE.getType().equalsIgnoreCase(acceptableType.getType()) &&
                    APPLICATION_JSON_API_TYPE.getSubtype().equalsIgnoreCase(acceptableType.getSubtype())) {
                result = true;
            }
        }
        return result;
    }

    private boolean isAcceptableContentType(ContainerRequestContext requestContext) {
        MediaType contentType = requestContext.getMediaType();
        return contentType == null || APPLICATION_JSON_API_TYPE.isCompatible(contentType);
    }

    private RequestParams createRequestParams(UriInfo uriInfo) {
        RequestParamsBuilder requestParamsBuilder = new RequestParamsBuilder(objectMapper);

        MultivaluedMap<String, String> queryParametersMultiMap = uriInfo.getQueryParameters();
        Map<String, String> queryParameters = new HashMap<>();

        for (String queryName : queryParametersMultiMap.keySet()) {
            queryParameters.put(queryName, queryParametersMultiMap.getFirst(queryName));
        }

        return requestParamsBuilder.buildRequestParams(queryParameters);
    }

    public RequestBody inputStreamToBody(InputStream is) throws IOException {
        if (is == null) {
            return null;
        }
        Scanner s = new Scanner(is).useDelimiter("\\A");
        String requestBody = s.hasNext() ? s.next() : "";
        if (requestBody == null || requestBody.isEmpty()) {
            return null;
        }
        return objectMapper.readValue(requestBody, RequestBody.class);
    }
}
