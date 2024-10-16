package aouiendyl.graphql.server.ktor

import aouiendyl.graphql.server.ktor.sdl.GraphQlSdl
import aouiendyl.graphql.server.ktor.sdl.graphQLSdlPostRoute
import com.expediagroup.graphql.server.ktor.defaultGraphQLStatusPages
import graphql.schema.GraphQLSchema
import graphql.schema.StaticDataFetcher
import graphql.schema.idl.RuntimeWiring.newRuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.Routing

fun Application.graphQLModule() {

    val schema = "type Query{hello: String}"
    val schemaParser = SchemaParser()
    val typeDefinitionRegistry = schemaParser.parse(schema)
    val runtimeWiring = newRuntimeWiring().type("Query") { builder ->
        builder.dataFetcher(
            "hello",
            StaticDataFetcher("world")
        )
    }.build()
    val schemaGenerator = SchemaGenerator()
    val graphQLSchema: GraphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)

    install(GraphQlSdl) {
        schema {
            gQlSchema = graphQLSchema
        }
    }
    install(Routing) {
        graphQLSdlPostRoute()
    }
    install(StatusPages) {
        defaultGraphQLStatusPages()
    }
}
