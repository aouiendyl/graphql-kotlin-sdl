package aouiendyl.graphql.server.ktor.sdl

import com.expediagroup.graphql.server.operations.Query
import graphql.execution.preparsed.NoOpPreparsedDocumentProvider
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring.newRuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GraphQLPluginConfigurationTest {

    @Test
    fun `verify exception will be thrown if preparsed document provider and APQs are configured`() {
        assertFailsWith<IllegalStateException> {
            embeddedServer(CIO, port = 0, module = Application::misconfiguredAPQGraphQLModule).start(wait = true)
        }
    }
}

class ConfigurationTestQuery : Query {
    fun foo(): String = TODO()
}

fun Application.misconfiguredAPQGraphQLModule() {
    install(GraphQlSdl) {
        schema {
            gQlSchema = buildSchema(confTestSchema)
        }
        engine {
            preparsedDocumentProvider = NoOpPreparsedDocumentProvider()
            automaticPersistedQueries {
                enabled = true
            }
        }
    }
}

private fun buildSchema(sdl: String): GraphQLSchema {
    val schemaParser = SchemaParser()
    val typeDefinitionRegistry = schemaParser.parse(sdl)

    val runtimeWiring = newRuntimeWiring().type("Query") { builder ->
        builder.dataFetcher("foo", ConfQueryDataFetcher())
    }.build()

    val schemaGenerator = SchemaGenerator()
    return schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
}

private class ConfQueryDataFetcher(): DataFetcher<String?> {
    override fun get(environment: DataFetchingEnvironment?): String? {
        return ConfigurationTestQuery().foo()
    }
}

val confTestSchema = """
    schema {
      query: Query
    }
    
    type Query {
      foo: String!
    }
""".trimIndent()