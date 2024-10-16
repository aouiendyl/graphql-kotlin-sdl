package aouiendyl.graphql.server.ktor.sdl

import com.apollographql.federation.graphqljava.tracing.FederatedTracingInstrumentation
import com.expediagroup.graphql.apq.provider.AutomaticPersistedQueriesProvider
import com.expediagroup.graphql.dataloader.instrumentation.syncexhaustion.DataLoaderSyncExecutionExhaustedInstrumentation
import com.expediagroup.graphql.generator.execution.FlowSubscriptionExecutionStrategy
import com.expediagroup.graphql.server.execution.GraphQLRequestHandler
import com.expediagroup.graphql.server.ktor.GraphQLConfiguration
import com.expediagroup.graphql.server.ktor.KtorGraphQLServer
import com.expediagroup.graphql.server.ktor.subscriptions.KtorGraphQLWebSocketServer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import graphql.execution.AsyncExecutionStrategy
import graphql.execution.AsyncSerialExecutionStrategy
import graphql.execution.instrumentation.ChainedInstrumentation
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.schema.GraphQLSchema
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.BaseApplicationPlugin
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import graphql.GraphQL as GraphQLEngine

/**
 * Ktor plugin that creates GraphQL server based on the provided [GraphQLConfiguration].
 *
 * A configuration of the `GraphQL` plugin might look as follows:
 * 1. Configure and install plugin
 *   ```kotlin
 *   install(GraphQL) {
 *      // your schema, engine and server configuration goes here
 *   }
 *   ```
 *
 * 2. Configure GraphQL routes
 *   ```kotlin
 *   routing {
 *      graphQLPostRoute()
 *   }
 *   ```
 *
 * @param config GraphQL configuration
 */
class GraphQlSdl(config: GraphQLSdlConfiguration) {

    val schema: GraphQLSchema = config.schema.gQlSchema

    val engine: GraphQLEngine = GraphQLEngine.newGraphQL(schema)
        .queryExecutionStrategy(AsyncExecutionStrategy(config.engine.exceptionHandler))
        .mutationExecutionStrategy(AsyncSerialExecutionStrategy(config.engine.exceptionHandler))
        .subscriptionExecutionStrategy(FlowSubscriptionExecutionStrategy(config.engine.exceptionHandler))
        .valueUnboxer(config.engine.idValueUnboxer)
        .also { builder ->
            config.engine.executionIdProvider?.let { builder.executionIdProvider(it) }

            var preparsedDocumentProvider: PreparsedDocumentProvider? = config.engine.preparsedDocumentProvider
            if (config.engine.automaticPersistedQueries.enabled) {
                if (preparsedDocumentProvider != null) {
                    throw IllegalStateException("Custom prepared document provider and APQ specified - disable APQ or don't specify the provider")
                } else {
                    preparsedDocumentProvider = AutomaticPersistedQueriesProvider(config.engine.automaticPersistedQueries.cache)
                }
            }
            preparsedDocumentProvider?.let { builder.preparsedDocumentProvider(it) }

            val instrumentations = mutableListOf<Instrumentation>()
            if (config.engine.batching.enabled) {
                builder.doNotAutomaticallyDispatchDataLoader()
                instrumentations.add(
                    when (config.engine.batching.strategy) {
                        GraphQLSdlConfiguration.BatchingStrategy.SYNC_EXHAUSTION -> DataLoaderSyncExecutionExhaustedInstrumentation()
                    }
                )
            }
            if (config.schema.federation.enabled && config.schema.federation.tracing.enabled) {
                instrumentations.add(FederatedTracingInstrumentation(FederatedTracingInstrumentation.Options(config.schema.federation.tracing.debug)))
            }

            instrumentations.addAll(config.engine.instrumentations)
            builder.instrumentation(ChainedInstrumentation(instrumentations))
        }
        .build()

    // TODO cannot override the request handler/server as it requires access to graphql engine
    private val requestHandler: GraphQLRequestHandler = GraphQLRequestHandler(
        graphQL = engine,
        dataLoaderRegistryFactory = config.engine.dataLoaderRegistryFactory
    )

    val server: KtorGraphQLServer = KtorGraphQLServer(
        requestParser = config.server.requestParser,
        contextFactory = config.server.contextFactory,
        requestHandler = requestHandler
    )

    val subscriptionServer: KtorGraphQLWebSocketServer by lazy {
        KtorGraphQLWebSocketServer(
            requestParser = config.server.subscriptions.requestParser,
            contextFactory = config.server.subscriptions.contextFactory,
            subscriptionHooks = config.server.subscriptions.hooks,
            requestHandler = requestHandler,
            initTimeoutMillis = config.server.subscriptions.connectionInitTimeout,
            objectMapper = jacksonObjectMapper().apply(config.server.jacksonConfiguration)
        )
    }

    companion object Plugin : BaseApplicationPlugin<Application, GraphQLSdlConfiguration, GraphQlSdl> {
        override val key: AttributeKey<GraphQlSdl> = AttributeKey("GraphQL")

        override fun install(pipeline: Application, configure: GraphQLSdlConfiguration.() -> Unit): GraphQlSdl {
            val config = GraphQLSdlConfiguration(pipeline.environment.config).apply(configure)
            return GraphQlSdl(config)
        }
    }
}

internal suspend inline fun KtorGraphQLServer.executeRequest(call: ApplicationCall) =
    execute(call.request)?.let {
        call.respond(it)
    } ?: call.respond(HttpStatusCode.BadRequest)
