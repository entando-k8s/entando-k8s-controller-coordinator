#!/usr/bin/env bash

version="$1"
#curl -f -o versions.yaml https://raw.githubusercontent.com/entando-k8s/entando-k8s-controller-coordinator/v${version}/charts/entando-k8s-controller-coordinator/templates/docker-image-info-configmap.yaml
#retcode=$?
#if [ $retcode -ne 0 ]; then
#  echo "Retrieving configmap from Openshift"
  oc get configmap -o yaml -n entando "entando-docker-image-info-v${1}" > versions.yaml
#fi
#
rm relatedImages.yaml
rm RELATED_IMAGES.yaml
rm images-to-certify.txt
rm values.yaml
IFS=$'\n'
rows=($(yq eval '.data' versions.yaml))
ignored_images="entando-de-app-wildfly entando-keycloak"
for row in "${rows[@]}"; do
  key=${row%: *}
  value=${row#*: }
  #strip single quotes
  value="$(eval echo $value)"
  echo "$value" > image-info.yaml
  version=$(yq eval  ".version" image-info.yaml)
  echo "processing $key"
  skopeo_result=$(skopeo inspect "docker://docker.io/entando/${key}:${version}" )
  name=$(echo $skopeo_result | jq '.Name')
  name=$(eval echo $name)
  digest=$(echo $skopeo_result | jq '.Digest')
  digest=$(eval echo $digest)
  env_var=$(echo ${key^^} | tr '-' '_')
  if  [[ $version =~ ^6\.3\.[0-9]+ ]] && ! [[ $ignored_images =~ $key ]]; then
    echo "    - name: ${key} #${version} " >> relatedImages.yaml
    echo "      image: ${name}@${digest}" >> relatedImages.yaml
    echo "                     - name: RELATED_IMAGE_${env_var} #${version} " >> RELATED_IMAGES.yaml
    echo "                       value: ${name}@${digest}" >> RELATED_IMAGES.yaml
    echo "docker.io/entando/${key}:${version}" >> images-to-certify.txt
  fi
  echo "    ${env_var,,}:" >> values.yaml
  echo "      sha256: ${digest#*:}" >> values.yaml
  echo "      version: ${version}" >> values.yaml
done