[![Build Status](https://img.shields.io/endpoint?url=https%3A%2F%2Fstatusbadge-jx.apps.serv.run%2Fentando-k8s%2Fentando-k8s-controller-coordinator)](https://github.com/entando-k8s/devops-results/tree/logs/jenkins-x/logs/entando-k8s/entando-k8s-controller-coordinator/master)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=entando-k8s_entando-k8s-controller-coordinator&metric=alert_status)](https://sonarcloud.io/dashboard?id=entando-k8s_entando-k8s-controller-coordinator)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=entando-k8s_entando-k8s-controller-coordinator&metric=coverage)](https://entando-k8s.github.io/devops-results/entando-k8s-controller-coordinator/master/jacoco/index.html)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=entando-k8s_entando-k8s-controller-coordinator&metric=vulnerabilities)](https://entando-k8s.github.io/devops-results/entando-k8s-controller-coordinator/master/dependency-check-report.html)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=entando-k8s_entando-k8s-controller-coordinator&metric=code_smells)](https://sonarcloud.io/dashboard?id=entando-k8s_entando-k8s-controller-coordinator)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=entando-k8s_entando-k8s-controller-coordinator&metric=security_rating)](https://sonarcloud.io/dashboard?id=entando-k8s_entando-k8s-controller-coordinator)
[![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=entando-k8s_entando-k8s-controller-coordinator&metric=sqale_index)](https://sonarcloud.io/dashboard?id=entando-k8s_entando-k8s-controller-coordinator)


# Entando Kubernetes Controller Coordinator

This project produces the Entando Kubernetes Controller Coordinator image. This is the entrypoint into the Entando Kubernetes Operator. It observes state changes that occur against Entando's Kubernetes Custom Resources and spins up the appropriate Kubernetes Controller image in response to such state changes.


