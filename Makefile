# -------------------------------
# Configuration
# -------------------------------
DATAHUB_VERSION := v0.13.3
DATAHUB_PATH := datahub/datahub-$(DATAHUB_VERSION)
DOCKERFILE_DIR := docker/datahub-gms
DOCKERHUB_USER := srchangb

TAG ?= custom

# -------------------------------
# Tasks
# -------------------------------
.PHONY: build-gms

build-gms:
	@echo "==> Building GMS in $(DATAHUB_PATH) ..."
	cd $(DATAHUB_PATH) && ./gradlew :metadata-service:war:build

	@echo "==> Building Docker image..."
	docker build -t datahub-gms:$(DATAHUB_VERSION)-$(TAG) -f docker/datahub-gms/Dockerfile .

	@echo "==> Docker image built: datahub-gms:$(DATAHUB_VERSION)-$(TAG)"

push-gms:
	@echo "==> Tagging image for Docker Hub..."
	docker tag datahub-gms:$(DATAHUB_VERSION)-$(TAG) $(DOCKERHUB_USER)/datahub-gms:$(DATAHUB_VERSION)-$(TAG)

	@echo "==> Pushing image to Docker Hub: $(DOCKERHUB_USER)/datahub-gms:$(DATAHUB_VERSION)-$(TAG)"
	docker push $(DOCKERHUB_USER)/datahub-gms:$(DATAHUB_VERSION)-$(TAG)

	@echo "✅ Done: Pushed image to Docker Hub"