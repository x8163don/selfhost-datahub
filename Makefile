# -------------------------------
# Configuration
# -------------------------------
DATAHUB_VERSION := v0.13.3
DATAHUB_PATH := datahub/datahub-$(DATAHUB_VERSION)
DOCKERFILE_DIR := docker/datahub-gms
IMAGE_NAME := datahub
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
	docker build -t $(IMAGE_NAME):$(DATAHUB_VERSION)-$(TAG) -f docker/datahub-gms/Dockerfile .

	@echo "==> Docker image built: $(IMAGE_NAME):$(DATAHUB_VERSION)-$(TAG)"

push-gms:
	@echo "==> Tagging image for Docker Hub..."
	docker tag $(IMAGE_NAME):$(DATAHUB_VERSION)-$(TAG) $(DOCKERHUB_USER)/$(IMAGE_NAME):$(DATAHUB_VERSION)-$(TAG)

	@echo "==> Pushing image to Docker Hub: $(DOCKERHUB_USER)/$(IMAGE_NAME):$(DATAHUB_VERSION)-$(TAG)"
	docker push $(DOCKERHUB_USER)/$(IMAGE_NAME):$(DATAHUB_VERSION)-$(TAG)

	@echo "✅ Done: Pushed image to Docker Hub"