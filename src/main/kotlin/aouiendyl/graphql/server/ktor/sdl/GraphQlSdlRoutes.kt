package aouiendyl.graphql.server.ktor.sdl

import com.expediagroup.graphql.generator.extensions.print
import com.expediagroup.graphql.server.execution.subscription.GRAPHQL_WS_PROTOCOL
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.ContentType
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.plugin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.application
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.flow.collect

/**
 * Configures GraphQL GET route
 *
 * @param endpoint GraphQL server GET endpoint, defaults to 'graphql'
 * @param streamingResponse Enable streaming response body without keeping it fully in memory. If set to true (default) it will set `Transfer-Encoding: chunked` header on the responses.
 * @param jacksonConfiguration Jackson Object Mapper customizations
 */
fun Route.graphQLSdlGetRoute(endpoint: String = "graphql", streamingResponse: Boolean = true, jacksonConfiguration: ObjectMapper.() -> Unit = {}): Route {
    val graphQLPlugin = this.application.plugin(GraphQlSdl)
    val route = get(endpoint) {
        graphQLPlugin.server.executeRequest(call)
    }
    route.install(ContentNegotiation) {
        jackson(streamRequestBody = streamingResponse) {
            apply(jacksonConfiguration)
        }
    }
    return route
}

/**
 * Configures GraphQL POST route
 *
 * @param endpoint GraphQL server POST endpoint, defaults to 'graphql'
 * @param streamingResponse Enable streaming response body without keeping it fully in memory. If set to true (default) it will set `Transfer-Encoding: chunked` header on the responses.
 * @param jacksonConfiguration Jackson Object Mapper customizations
 */
fun Route.graphQLSdlPostRoute(endpoint: String = "graphql", streamingResponse: Boolean = true, jacksonConfiguration: ObjectMapper.() -> Unit = {}): Route {
    val graphQLPlugin = this.application.plugin(GraphQlSdl)
    val route = post(endpoint) {
        graphQLPlugin.server.executeRequest(call)
    }
    route.install(ContentNegotiation) {
        jackson(streamRequestBody = streamingResponse) {
            apply(jacksonConfiguration)
        }
    }
    return route
}

/**
 * Configures GraphQL subscriptions route
 *
 * @param endpoint GraphQL server subscriptions endpoint, defaults to 'subscriptions'
 */
fun Route.graphQLSdlSubscriptionsRoute(
    endpoint: String = "subscriptions"
) {
    webSocket(path = endpoint, protocol = GRAPHQL_WS_PROTOCOL) {
        this.application.plugin(GraphQlSdl)
            .subscriptionServer
            .handleSubscription(this)
            .collect()
    }
}

/**
 * Configures GraphQL SDL route.
 *
 * @param endpoint GET endpoint that will return GraphQL schema in SDL format, defaults to 'sdl'
 */
fun Route.graphQLSdlSDLRoute(endpoint: String = "sdl"): Route {
    val graphQLPlugin = this.application.plugin(GraphQlSdl)
    val sdl = graphQLPlugin.schema.print()
    return get(endpoint) {
        call.respondText(text = sdl)
    }
}

/**
 * Configures GraphiQL IDE route.
 *
 * @param endpoint GET endpoint that will return instance of GraphiQL IDE, defaults to 'graphiql'
 * @param graphQLEndpoint your GraphQL endpoint for processing requests
 * @param subscriptionsEndpoint your GraphQL subscriptions endpoint
 */
fun Route.graphiQLSdlRoute(
    endpoint: String = "graphiql",
    graphQLEndpoint: String = "graphql",
    subscriptionsEndpoint: String = "subscriptions",
): Route {
    val contextPath = this.environment?.rootPath
    val graphiQL = GraphQlSdl::class.java.classLoader.getResourceAsStream("graphql-graphiql.html")?.bufferedReader()?.use { reader ->
        reader.readText()
            .replace("\${graphQLEndpoint}", if (contextPath.isNullOrBlank()) graphQLEndpoint else "$contextPath/$graphQLEndpoint")
            .replace("\${subscriptionsEndpoint}", if (contextPath.isNullOrBlank()) subscriptionsEndpoint else "$contextPath/$subscriptionsEndpoint")
    } ?: throw IllegalStateException("Unable to load GraphiQL")
    return get(endpoint) {
        call.respondText(graphiQL, ContentType.Text.Html)
    }
}