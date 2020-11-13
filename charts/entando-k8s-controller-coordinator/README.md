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

### Other environment variables

*  ENTANDO_DEFAULT_ROUTING_SUFFIX: This is the default domain name suffix that will be used to determine a valid hostname for those Entando Custom Resources that result in an Ingress being created. This is only needed as a 'fallback'  as all Entando Custom Resources that result in Ingress would also allow you to specify a custom Ingress hostname on the resource itself. When this property has not been provided on the Entando Custom Resource itself, a hostname will be generated using the name and the namespace of the resource separated by a `-`, e.g. `my-app-mynamespace.your-routing-suffix.com`
*  ENTANDO_POD_READINESS_TIMEOUT_SECONDS: The time for the operator to wait for Pods that deploy a service before timing out
*  ENTANDO_POD_COMPLETION_TIMEOUT_SECONDS: The time for the operator to wait for run-to-completion Pods
*  ENTANDO_DISABLE_KEYCLOAK_SSL_REQUIREMENT: "true" if Keycloak does not need to suport HTTPS, such as for demos or POC's
*  ENTANDO_K8S_OPERATOR_SECURITY_MODE: If it is "lenient", the Operator will attempt to create certain sensitive resources such as ServiceAccounts, Roles and RoleBindings as needed. If "strict" this Helm Chart itself should ensure they have already been created.

## `tls`

This section is used to configuring global TLS settings for the Entando Operator being deployed. These settings are automatically propagated to individual Entando Kubernetes Controllers and are then applied to all Deployments that are created by the Entando Operator. Two sets of certificates are involved here, a custom trusted Certifying Authority, and the internal certificate/key pair to expose individual Ingresses securely using HTTPS.

### `tls.caCertSecretName`

The name of an Opaque Secret that contains single certificates for every key. There is no constraint on the name of
the keys here, as long as every entry only contains a single certificate.   

### `tls.tlsSecretName`

The name of a standard TLS Secret that contains two keys: tls.crt and tls.key. This certificate will be used for all





