package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks
import io.envoyproxy.envoy.api.v2.DiscoveryRequest
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest as v3DiscoveryRequest
import io.envoyproxy.envoy.api.v2.DiscoveryResponse
import io.envoyproxy.envoy.api.v2.core.Node
import pl.allegro.tech.servicemesh.envoycontrol.protocol.HttpMethod
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.ADS
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class AllDependenciesValidationException(serviceName: String?)
    : NodeMetadataValidationException(
    "Blocked service $serviceName from using all dependencies. Only defined services can use all dependencies"
)

class WildcardPrincipalValidationException(serviceName: String?)
    : NodeMetadataValidationException(
        "Blocked service $serviceName from allowing everyone in incoming permissions. " +
                "Only defined services can use that."
)

class WildcardPrincipalMixedWithOthersValidationException(serviceName: String?)
    : NodeMetadataValidationException(
        "Blocked service $serviceName from allowing everyone in incoming permissions. " +
                "Either a wildcard or a list of clients must be provided."
)

class InvalidHttpMethodValidationException(serviceName: String?, method: String)
    : NodeMetadataValidationException(
        "Service: $serviceName defined an unknown method: $method in endpoint permissions."
)

class ConfigurationModeNotSupportedException(serviceName: String?, mode: String)
    : NodeMetadataValidationException(
    "Blocked service $serviceName from receiving updates. $mode is not supported by server."
)

class NodeMetadataValidator(
    val properties: SnapshotProperties
) : DiscoveryServerCallbacks {
    companion object {
        private val logger by logger()
    }

    private val tlsProperties = properties.incomingPermissions.tlsAuthentication

    override fun onStreamClose(streamId: Long, typeUrl: String?) {}

    override fun onStreamCloseWithError(streamId: Long, typeUrl: String?, error: Throwable?) {}

    override fun onStreamOpen(streamId: Long, typeUrl: String?) {}

    override fun onV3StreamRequest(streamId: Long, request: v3DiscoveryRequest?) {
        request?.node?.let { validateV3Metadata(it) }
    }

    override fun onV2StreamRequest(streamId: Long, request: DiscoveryRequest?) {
        request?.node?.let { validateV2Metadata(it) }
    }

    override fun onStreamResponse(
        streamId: Long,
        request: DiscoveryRequest?,
        response: DiscoveryResponse?
    ) {
    }

    private fun validateV3Metadata(node: io.envoyproxy.envoy.config.core.v3.Node) {
        // Some validation logic is executed when NodeMetadata is created.
        // This may throw NodeMetadataValidationException
        val metadata = NodeMetadata(node.metadata, properties)

        validateMetadata(metadata)
    }

    private fun validateV2Metadata(node: Node) {
        // Some validation logic is executed when NodeMetadata is created.
        // This may throw NodeMetadataValidationException
        val metadata = NodeMetadata(node.metadata, properties)

        validateMetadata(metadata)
    }

    private fun validateMetadata(metadata: NodeMetadata) {
        validateDependencies(metadata)
        validateIncomingEndpoints(metadata)
        validateConfigurationMode(metadata)
    }

    private fun validateDependencies(metadata: NodeMetadata) {
        if (!properties.outgoingPermissions.enabled) {
            return
        }
        validateEndpointPermissionsMethods(metadata)
        if (hasAllServicesDependencies(metadata) && !isAllowedToHaveAllServiceDependencies(metadata)) {
            throw AllDependenciesValidationException(metadata.serviceName)
        }
    }

    private fun validateIncomingEndpoints(metadata: NodeMetadata) {
        if (!properties.incomingPermissions.enabled) {
            return
        }

        metadata.proxySettings.incoming.endpoints.forEach { incomingEndpoint ->
            val clients = incomingEndpoint.clients.map { it.name }

            if (clients.isEmpty() && incomingEndpoint.unlistedClientsPolicy != Incoming.UnlistedPolicy.LOG) {
                logger.warn("An incoming endpoint definition for service: ${metadata.serviceName}" +
                    ", path: ${incomingEndpoint.path} does not have any clients defined." +
                    "It means that no one will be able to contact that endpoint")
            }

            if (clients.contains(tlsProperties.wildcardClientIdentifier)) {
                if (clients.size != 1) {
                    throw WildcardPrincipalMixedWithOthersValidationException(metadata.serviceName)
                }
                if (metadata.serviceName !in tlsProperties.servicesAllowedToUseWildcard) {
                    throw WildcardPrincipalValidationException(metadata.serviceName)
                }
            }
        }
    }

    /**
     * Exception is logged in DiscoveryRequestStreamObserver.onNext()
     * @see io.envoyproxy.controlplane.server.DiscoveryRequestStreamObserver.onNext()
     */
    @Suppress("SwallowedException")
    private fun validateEndpointPermissionsMethods(metadata: NodeMetadata) {
        metadata.proxySettings.incoming.endpoints.forEach { incomingEndpoint ->
            incomingEndpoint.methods.forEach { method ->
                try {
                    HttpMethod.valueOf(method)
                } catch (e: Exception) {
                    throw InvalidHttpMethodValidationException(metadata.serviceName, method)
                }
            }
        }
    }

    private fun hasAllServicesDependencies(metadata: NodeMetadata) =
        metadata.proxySettings.outgoing.allServicesDependencies

    private fun isAllowedToHaveAllServiceDependencies(metadata: NodeMetadata) = properties
        .outgoingPermissions.servicesAllowedToUseWildcard.contains(metadata.serviceName)

    private fun validateConfigurationMode(metadata: NodeMetadata) {
        if (metadata.communicationMode == ADS && !properties.enabledCommunicationModes.ads) {
            throw ConfigurationModeNotSupportedException(metadata.serviceName, "ADS")
        }
        if (metadata.communicationMode == XDS && !properties.enabledCommunicationModes.xds) {
            throw ConfigurationModeNotSupportedException(metadata.serviceName, "XDS")
        }
    }
}
