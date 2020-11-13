version=$1
registry=$2
curl -o versions.yaml https://raw.githubusercontent.com/entando-k8s/entando-k8s-controller-coordinator/v${version}/charts/entando-k8s-controller-coordinator/templates/docker-image-info-configmap.yaml

docker pull entando/entando-k8s-controller-coordinator:${version}
docker tag  entando/entando-k8s-controller-coordinator:${version} ${registry}/entando/entando-k8s-controller-coordinator:${version}
docker push ${registry}/entando/entando-k8s-controller-coordinator:${version}
echo "entando/entando-k8s-controller-coordinator:${version}"
keys=$(cat versions.yaml| yq r - 'data'  | yq r - --printMode p  '*')
for key in $keys; do
    value=$(cat versions.yaml| yq r - "data.${key}")
    version=$( echo $value | yq r - "version")
    docker pull entando/${key}:${version}
    docker tag  entando/${key}:${version} ${registry}/entando/${key}:${version}
    docker push ${registry}/entando/${key}:${version}
done