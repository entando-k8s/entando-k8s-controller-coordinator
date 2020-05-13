# The Entando Kubernetes Operator

This chart installs the Entando Kubernetes Operator. The default values.yaml file can be modified to configure how the Entando Kubernetes Operator behaves in the deployment environment. The following sections in the values.yaml file are of particular significance.

# `env`

This section contains a map of name/value pairs that will be used to run the entando-k8s-controller-coordinator image on Kubernetes. The majority of these variables will be propagated to the individual Entando Kubernetes Controller. In this specific chart, many of the possible environment variables are already set. The one major group of variables that have not been set are the variables involved in resolving Docker images when deploying Entando components.

## How it resolves Docker images

In order to support development of both Controller images and supporting images such as sidecars, we have established
a very flexible Docker image resolution process. The eventual image URI that we resolve has 4 segments to it: 
 * a Docker registry
 * a Docker (or Openshift) registry namespace (or organization depending on the registry implementation)
 * the image name
 * a version suffix.
Entando looks for 'fallback' (default value when no value could be resolved) and 'overriding' configuration settings in the OS Environment Variables 
to resolve the full image URI. It also inspects a Kubernetes ConfigMap specified in the `ENTANDO_DOCKER_IMAGE_INFO_CONFIGMAP` environment variable. In this specific chart, the value of this variable is specified as omitted by default and would resolve to the name of the Configmap provided in the chart itself, `entando-docker-image-info`. This configmap stores 
a JSON formatted image configuration against known image names. This ConfigMap can be created in the Operator's namespace.
For images in the `entando` namespace, the registry, namespace and version segments of the image URI can be overridden 
following a similar pattern. (The namespace used to resolve this ConfigMap can be overridden by using the `ENTANDO_K8S_OPERATOR_CONFIGMAP_NAMESPACE` environment variable/system property)

The Docker registry segment will be resolved as follows:
 * If a global override, e.g. ENTANDO_DOCKER_REGISTRY_OVERRIDE=docker.io., was configured, it will always be used.
 * An entry in the standard ImageVersionsConfigMap against the image name, e.g. my-image:{registry:docker.io}, will be used when the aforementioned override is absent
 * A default, e.g. ENTANDO_DOCKER_REGISTRY_FALLBACK=docker.io, will be used if nothing else was specified.

The Docker namespace/organization segment will be resolved as follows:
 * If a global override, e.g. ENTANDO_DOCKER_IMAGE_ORG_OVERRIDE=test-org, was configured, it will always be used
 * An entry in the standard ImageVersionsConfigMap against the image name, e.g. my-image:{organization:test-org}, will be used when the aforementioned override is absent
 * A default, e.g. ENTANDO_DOCKER_IMAGE_ORG_FALLBACK=test-org, will be used if nothing else was specified.

The image version segment will be resolved as follows:
 * If a global override, e.g. ENTANDO_DOCKER_IMAGE_VERSION_OVERRIDE=6.0.14, was configured, it will always be used
 * An entry in the standard ImageVersionsConfigMap against the image name, e.g. my-image:{version:6.0.14}, will be used when the aforementioned override  is absent
 * A default, e.g. ENTANDO_DOCKER_IMAGE_VERSION=6.0.14, will be used if nothing else was specified.

## `tls`

This section is used to configuring global TLS settings for the Entando Operator being deployed. These settings are automatically propagated to individual Entando Kubernetes Controllers and are then applied to all Deployments that are created by the Entando Operator. Two sets of certificates are involved here, a custom trusted Certifying Authority, and the internal certificate/key pair to expose individual Ingresses securely using HTTPS.

### `tls.caCrt`

The certificate of a custom Certifying Authority (CA) that will be added to the list of standard CA's generally acknowledged by Linux distributions and Java Virtual Machines. Only override this setting if your CA is not linked to the standard CA's via a certificate chain. This is particularly useful for scenarios where a self signed certificate is used as a Certificate Authority. As with all binary content in YAML, this property should be Base64 encoded.

### `tls.tlsCrt`

The Certificate to use when exposing Ingresses over HTTPS. This should in fact be the entire Certificate chain that links your specific Certificate to one of the standard CA's, or alternatively to the the custom CA certificate provided above. IN most of our custom resources that result in an Ingress, you will be allowed to specify an alternative Kubernetes TLS secret to use, but where no such secret is specified, a Kubernetes TLS secret will be created using this propery to create a TLS secret to be used for the Ingress. As with all binary content in YAML, this property should be Base64 encoded.  If this property is absent, no default TLS secret will be created for Ingresses

### `tls.tlsKey`

The private key used when the above TLS certificate was created. This will be used as the private key when exposing Ingresses over HTTPS.  As above, you will be allowed to specify an alternative Kubernetes TLS secret for your HTTPS Ingresses. As with all binary content in YAML, this property should be Base64 encoded. If this property is absent, no default TLS secret will be created for Ingresses



