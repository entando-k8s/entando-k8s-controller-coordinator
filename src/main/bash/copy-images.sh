#!/usr/bin/env bash

DRY_RUN=false; [ "$1" = "--dry-run" ] && DRY_RUN=true && shift

version="$1"
registry="$2"
curl -f -o versions.yaml https://raw.githubusercontent.com/entando-k8s/entando-k8s-controller-coordinator/v${version}/charts/entando-k8s-controller-coordinator/templates/docker-image-info-configmap.yaml
retcode=$?
if [ $retcode -ne 0 ]; then
  echo "Retrieving configmap from Openshift"
  oc get configmap -o yaml -n entando "entando-docker-image-info-v${version}" > versions.yaml
fi

if $DRY_RUN; then
  echo "docker pull entando/entando-k8s-controller-coordinator:${version}"
  echo "docker tag  entando/entando-k8s-controller-coordinator:${version} ${registry}/entando/entando-k8s-controller-coordinator:${version}"
  echo "docker push ${registry}/entando/entando-k8s-controller-coordinator:${version}"
else
  docker pull "entando/entando-k8s-controller-coordinator:${version}"
  docker tag  "entando/entando-k8s-controller-coordinator:${version}" "${registry}/entando/entando-k8s-controller-coordinator:${version}"
  docker push "${registry}/entando/entando-k8s-controller-coordinator:${version}"
fi
rm relatedImages.yaml
rm RELATED_IMAGES.yaml
IFS=$'\n'
rows=($(yq eval '.data' versions.yaml))
for row in "${rows[@]}"; do
  key=${row%: *}
  value=${row#*: }
  #strip single quotes
  value="$(eval echo $value)"
  echo "$value" > image-info.yaml
  version=$(yq eval  ".version" image-info.yaml)
  if $DRY_RUN; then
    echo "docker pull entando/${key}:${version}"
    echo "docker tag entando/${key}:${version} ${registry}/entando/${key}:${version}"
    echo "docker push ${registry}/entando/${key}:${version}"
  else
    docker pull "entando/${key}:${version}"
    docker tag  "entando/${key}:${version}" "${registry}/entando/${key}:${version}"
    docker push "${registry}/entando/${key}:${version}"
  fi
done