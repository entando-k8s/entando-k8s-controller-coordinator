# Entando Kubernetes Controller Coordinator

This project produces the Entando Kubernetes Controller Coordinator image. This is the entrypoint into the Entando Kubernetes Operator. It observes state changes that occur against Entando's Kubernetes Custom Resources and spins up the appropriate Kubernetes Controller image in response to such state changes.

# How to build

```
mvn clean package -Pjvm
docker build . -f Dockerfile.jvm -t entando/entando-k8s-controller-coordinator:6.3.999
```


