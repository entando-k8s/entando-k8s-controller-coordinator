#!/bin/bash
set -e

# Validate required environment variables
if [[ -z "$NEXUS_URL" || -z "$NEXUS_REPO_ID" ]]; then
  echo "::error::Missing required environment variables: NEXUS_URL or NEXUS_REPO_ID"
  exit 1
fi

echo "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
echo " PUBLISH TO NEXUS"
echo "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"

mvn -B javadoc:jar source:jar source:test-jar deploy \
  -DskipTests=true \
  -DaltDeploymentRepository="${NEXUS_REPO_ID}::${NEXUS_URL}" \
  -Pprepare-for-nexus \
  -DskipPreDeploymentTests=true \
  -DskipPostDeploymentTests=true \
  -Ddependency-check.skip=true

echo "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
echo " PUBLISH COMPLETE"
echo "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"