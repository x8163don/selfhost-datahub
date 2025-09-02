import contextlib
import logging
import os
import subprocess
from typing import Callable, Iterator, List, Optional, Union

import pytest
import pytest_docker.plugin

logger = logging.getLogger(__name__)


def is_responsive(container_name: str, port: int, hostname: Optional[str]) -> bool:
    """A cheap way to figure out if a port is responsive on a container"""
    if hostname:
        cmd = f"docker exec {container_name} /bin/bash -c 'echo -n > /dev/tcp/{hostname}/{port}'"
    else:
        # use the hostname of the container
        cmd = f"docker exec {container_name} /bin/bash -c 'c_host=`hostname`;echo -n > /dev/tcp/$c_host/{port}'"
    ret = subprocess.run(
        cmd,
        shell=True,
    )
    return ret.returncode == 0


def wait_for_port(
    docker_services: pytest_docker.plugin.Services,
    container_name: str,
    container_port: int,
    hostname: Optional[str] = None,
    timeout: float = 30.0,
    pause: float = 0.5,
    checker: Optional[Callable[[], bool]] = None,
) -> None:
    try:
        docker_services.wait_until_responsive(
            timeout=timeout,
            pause=pause,
            check=(
                checker
                if checker
                else lambda: is_responsive(container_name, container_port, hostname)
            ),
        )
        logger.info(f"Container {container_name} is ready!")
    finally:
        # use check=True to raise an error if command gave bad exit code
        subprocess.run(f"docker logs {container_name}", shell=True, check=True)


@pytest.fixture(scope="session")
def docker_compose_command():
    """Docker Compose command to use, it could be either `docker-compose`
    for Docker Compose v1 or `docker compose` for Docker Compose
    v2."""

    return "docker compose"


@pytest.fixture(scope="module")
def docker_compose_runner(
    docker_compose_command, docker_compose_project_name, docker_setup, docker_cleanup
):
    @contextlib.contextmanager
    def run(
        compose_file_path: Union[str, List[str]], key: str, cleanup: bool = True
    ) -> Iterator[pytest_docker.plugin.Services]:
        with pytest_docker.plugin.get_docker_services(
            docker_compose_command=docker_compose_command,
            # We can remove the type ignore once this is merged:
            # https://github.com/avast/pytest-docker/pull/108
            docker_compose_file=compose_file_path,  # type: ignore
            docker_compose_project_name=f"{docker_compose_project_name}-{key}",
            docker_setup=docker_setup,
            docker_cleanup=docker_cleanup if cleanup else [],
        ) as docker_services:
            yield docker_services

    return run


def cleanup_image(image_name: str) -> None:
    assert ":" not in image_name, "image_name should not contain a tag"

    if not os.environ.get("CI"):
        logger.debug("Not cleaning up images to speed up local development")
        return

    images_proc = subprocess.run(
        f"docker image ls --filter 'reference={image_name}*' -q",
        shell=True,
        capture_output=True,
        text=True,
        check=True,
    )

    if not images_proc.stdout:
        logger.debug(f"No images to cleanup for {image_name}")
        return

    image_ids = images_proc.stdout.splitlines()
    subprocess.run(
        f"docker image rm {' '.join(image_ids)}",
        shell=True,
        check=True,
    )
