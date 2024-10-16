package aouiendyl.graphql.server.ktor.sdl

import com.expediagroup.graphql.server.ktor.defaultGraphQLStatusPages
import com.expediagroup.graphql.server.operations.Query
import com.expediagroup.graphql.server.operations.Subscription
import com.expediagroup.graphql.server.types.GraphQLBatchRequest
import com.expediagroup.graphql.server.types.GraphQLRequest
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring.newRuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.Routing
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GraphQLPluginTest {

    @Test
    fun `SDL route test`() {
        val expectedSchema = """
            schema {
              query: Query
              subscription: Subscription
            }

            "Marks the field, argument, input field or enum value as deprecated"
            directive @deprecated(
                "The reason for the deprecation"
                reason: String = "No longer supported"
              ) on FIELD_DEFINITION | ARGUMENT_DEFINITION | ENUM_VALUE | INPUT_FIELD_DEFINITION

            "Directs the executor to include this field or fragment only when the `if` argument is true"
            directive @include(
                "Included when true."
                if: Boolean!
              ) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT

            "Indicates an Input Object is a OneOf Input Object."
            directive @oneOf on INPUT_OBJECT

            "Directs the executor to skip this field or fragment when the `if` argument is true."
            directive @skip(
                "Skipped when true."
                if: Boolean!
              ) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT

            "Exposes a URL that specifies the behaviour of this scalar."
            directive @specifiedBy(
                "The URL that specifies the behaviour of this scalar."
                url: String!
              ) on SCALAR

            type Query {
              hello(name: String): String!
            }

            type Subscription {
              flow: Int!
            }
        """.trimIndent()
        testApplication {
            val response = client.get("/sdl")
            assertEquals(HttpStatusCode.OK, response.status)
            println("schema: ${response.bodyAsText().trim()}")
            assertEquals(expectedSchema, response.bodyAsText().trim())
        }
    }

    @Test
    fun `server should handle valid GET requests`() {
        testApplication {
            val response = client.get("/graphql") {
                parameter("query", "query HelloQuery(\$name: String){ hello(name: \$name) }")
                parameter("operationName", "HelloQuery")
                parameter("variables", """{"name":"junit"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"data":{"hello":"Hello junit"}}""", response.bodyAsText().trim())
        }
    }

    @Test
    fun `server should return Method Not Allowed for Mutation GET requests`() {
        testApplication {
            val response = client.get("/graphql") {
                parameter("query", "mutation { foo }")
            }
            assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        }
    }

    @Test
    fun `server should return Bad Request for invalid GET requests`() {
        testApplication {
            val response = client.get("/graphql")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `server should handle valid POST requests`() {
        testApplication {
            val client = createClient {
                install(ContentNegotiation) {
                    jackson()
                }
            }
            val response = client.post("/graphql") {
                contentType(ContentType.Application.Json)
                setBody(GraphQLRequest(query = "query HelloQuery(\$name: String){ hello(name: \$name) }", operationName = "HelloQuery", variables = mapOf("name" to "junit")))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"data":{"hello":"Hello junit"}}""", response.bodyAsText().trim())
        }
    }

    @Test
    fun `server should handle valid POST batch requests`() {
        testApplication {
            val client = createClient {
                install(ContentNegotiation) {
                    jackson()
                }
            }
            val response = client.post("/graphql") {
                contentType(ContentType.Application.Json)
                setBody(
                    GraphQLBatchRequest(
                        listOf(
                            GraphQLRequest(query = "query HelloWorldQuery { hello }"),
                            GraphQLRequest(query = "query HelloQuery(\$name: String){ hello(name: \$name) }", operationName = "HelloQuery", variables = mapOf("name" to "junit"))
                        )
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""[{"data":{"hello":"Hello World"}},{"data":{"hello":"Hello junit"}}]""", response.bodyAsText().trim())
        }
    }

    @Test
    fun `server should return Bad Request for invalid POST requests with correct content type`() {
        testApplication {
            val response = client.post("/graphql") {
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `server should return Unsupported Media Type for POST requests with invalid content type`() {
        testApplication {
            val response = client.post("/graphql")
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        }
    }

    @Test
    fun `server should handle subscription requests`() {
        testApplication {
            val client = createClient {
                install(ContentNegotiation) {
                    jackson()
                }
                install(WebSockets)
            }

            client.webSocket({
                url("/subscriptions")
                headers[HttpHeaders.SecWebSocketProtocol] = "graphql-transport-ws"
            }) {
                outgoing.send(Frame.Text("""{"type": "connection_init"}"""))

                val ack = incoming.receive()
                assertIs<Frame.Text>(ack)
                assertEquals("""{"type":"connection_ack"}""", ack.readText())

                outgoing.send(Frame.Text("""{"type": "subscribe", "id": "unique-id", "payload": { "query": "subscription { flow }" }}"""))

                assertEquals("""{"id":"unique-id","payload":{"data":{"flow":1}},"type":"next"}""", (incoming.receive() as? Frame.Text)?.readText())
                assertEquals("""{"id":"unique-id","payload":{"data":{"flow":2}},"type":"next"}""", (incoming.receive() as? Frame.Text)?.readText())
                assertEquals("""{"id":"unique-id","payload":{"data":{"flow":3}},"type":"next"}""", (incoming.receive() as? Frame.Text)?.readText())
                assertEquals("""{"id":"unique-id","type":"complete"}""", (incoming.receive() as? Frame.Text)?.readText())
            }
        }
    }

    @Test
    fun `server should provide GraphiQL endpoint`() {
        testApplication {
            val response = client.get("/graphiql")
            assertEquals(HttpStatusCode.OK, response.status)

            val html = response.bodyAsText()
            assertContains(html, "var serverUrl = '/graphql';")
            assertContains(html, """var subscriptionUrl = new URL("/subscriptions", location.href);""")
        }
    }
}

fun Application.testGraphQLModule() {

    val testSchema = """
        schema {
          query: Query
          subscription: Subscription
        }
        
        type Query {
          hello(name: String): String!
        }
    
        type Subscription {
          flow: Int!
        }
    """.trimIndent()

    class TestQuery : Query {
        fun hello(name: String? = null): String = if (name == null) {
            "Hello World"
        } else {
            "Hello $name"
        }
    }

    class TestSubscription : Subscription {
        fun flow() = flowOf(1, 2, 3)
    }

    class PluginQueryDataFetcher(): DataFetcher<String> {
        override fun get(environment: DataFetchingEnvironment?): String {
            return environment?.let {
                val name = environment.getArgument<String?>("name")
                TestQuery().hello(name)
            } ?: throw IllegalStateException()
        }
    }

    class PluginSubscriptionDataFetcher(): DataFetcher<Flow<Int>> {
        override fun get(environment: DataFetchingEnvironment?): Flow<Int> {
            return TestSubscription().flow()
        }
    }

    fun buildSchema(sdl: String): GraphQLSchema {
        val schemaParser = SchemaParser()
        val typeDefinitionRegistry = schemaParser.parse(sdl)

        val runtimeWiring = newRuntimeWiring().type("Query") { builder ->
            builder.dataFetcher("hello", PluginQueryDataFetcher())
        }.type("Subscription") { builder ->
            builder.dataFetcher("flow", PluginSubscriptionDataFetcher())
        }.build()

        val schemaGenerator = SchemaGenerator()
        return schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
    }


    install(StatusPages) {
        defaultGraphQLStatusPages()
    }
    install(GraphQlSdl) {
        schema {
            gQlSchema = buildSchema(testSchema)
        }
    }
    install(io.ktor.server.websocket.WebSockets)
    install(Routing) {
        graphQLSdlGetRoute()
        graphQLSdlPostRoute()
        graphQLSdlSubscriptionsRoute()
        graphQLSdlSDLRoute()
        graphiQLSdlRoute()
    }
}
