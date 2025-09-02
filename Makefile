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

build-upgrade:
	@echo "==> Building GMS in $(DATAHUB_PATH) ..."
	cd $(DATAHUB_PATH) && ./gradlew :datahub-upgrade:build

	@echo "==> Building Docker image..."
	docker build -t datahub-upgrade:$(DATAHUB_VERSION)-$(TAG) -f docker/datahub-upgrade/Dockerfile .

	@echo "==> Docker image built: datahub-gms:$(DATAHUB_VERSION)-$(TAG)"

push-upgrade:
	@echo "==> Tagging image for Docker Hub..."
	docker tag datahub-upgrade:$(DATAHUB_VERSION)-$(TAG) $(DOCKERHUB_USER)/datahub-upgrade:$(DATAHUB_VERSION)-$(TAG)

	@echo "==> Pushing image to Docker Hub: $(DOCKERHUB_USER)/datahub-gms:$(DATAHUB_VERSION)-$(TAG)"
	docker push $(DOCKERHUB_USER)/datahub-upgrade:$(DATAHUB_VERSION)-$(TAG)

	@echo "✅ Done: Pushed image to Docker Hub"


build-mae:
	@echo "==> Building GMS in $(DATAHUB_PATH) ..."
	cd $(DATAHUB_PATH) && ./gradlew :metadata-jobs:mae-consumer:build

	@echo "==> Building Docker image..."
	docker build -t datahub-mae-consumer:$(DATAHUB_VERSION)-$(TAG) -f docker/datahub-mae/Dockerfile .

	@echo "==> Docker image built: datahub-gms:$(DATAHUB_VERSION)-$(TAG)"

push-mae:
	@echo "==> Tagging image for Docker Hub..."
	docker tag datahub-mae-consumer:$(DATAHUB_VERSION)-$(TAG) $(DOCKERHUB_USER)/datahub-mae-consumer:$(DATAHUB_VERSION)-$(TAG)

	@echo "==> Pushing image to Docker Hub: $(DOCKERHUB_USER)/datahub-gms:$(DATAHUB_VERSION)-$(TAG)"
	docker push $(DOCKERHUB_USER)/datahub-mae-consumer:$(DATAHUB_VERSION)-$(TAG)

	@echo "✅ Done: Pushed image to Docker Hub"

build-mce:
	@echo "==> Building GMS in $(DATAHUB_PATH) ..."
	cd $(DATAHUB_PATH) && ./gradlew :metadata-jobs:mce-consumer:build

	@echo "==> Building Docker image..."
	docker build -t datahub-mce-consumer:$(DATAHUB_VERSION)-$(TAG) -f docker/datahub-mce/Dockerfile .

	@echo "==> Docker image built: datahub-gms:$(DATAHUB_VERSION)-$(TAG)"

push-mce:
	@echo "==> Tagging image for Docker Hub..."
	docker tag datahub-mce-consumer:$(DATAHUB_VERSION)-$(TAG) $(DOCKERHUB_USER)/datahub-mce-consumer:$(DATAHUB_VERSION)-$(TAG)

	@echo "==> Pushing image to Docker Hub: $(DOCKERHUB_USER)/datahub-gms:$(DATAHUB_VERSION)-$(TAG)"
	docker push $(DOCKERHUB_USER)/datahub-mce-consumer:$(DATAHUB_VERSION)-$(TAG)

	@echo "✅ Done: Pushed image to Docker Hub"