import shutil
from typing import List
from unittest import mock

import pytest
from freezegun import freeze_time
from great_expectations.data_context.data_context.file_data_context import (
    FileDataContext,
)

from datahub.emitter.mcp import MetadataChangeProposalWrapper
from datahub.ingestion.sink.file import write_metadata_file
from tests.test_helpers import mce_helpers
from tests.test_helpers.docker_helpers import wait_for_port

FROZEN_TIME = "2021-12-28 12:00:00"


class MockDatahubEmitter:
    def __init__(self, gms_server: str):
        self.mcps: List[MetadataChangeProposalWrapper] = []

    def emit_mcp(self, mcp: MetadataChangeProposalWrapper) -> None:
        self.mcps.append(mcp)

    def write_to_file(self, filename):
        write_metadata_file(filename, self.mcps)


@freeze_time(FROZEN_TIME)
@pytest.mark.integration
@pytest.mark.parametrize(
    "checkpoint, golden_json",
    [
        ("test_checkpoint", "ge_mcps_golden.json"),
        ("test_checkpoint_2", "ge_mcps_golden_2.json"),
    ],
)
def test_ge_ingest(
    docker_compose_runner,
    pytestconfig,
    tmp_path,
    mock_time,
    checkpoint,
    golden_json,
    **kwargs,
):
    test_resources_dir = pytestconfig.rootpath / "tests/integration/great-expectations"

    with docker_compose_runner(
        test_resources_dir / "docker-compose.yml", "great-expectations"
    ) as docker_services, mock.patch(
        "datahub.emitter.rest_emitter.DatahubRestEmitter.emit_mcp"
    ) as mock_emit_mcp:
        wait_for_port(docker_services, "ge_postgres", 5432)

        emitter = MockDatahubEmitter("")
        mock_emit_mcp.side_effect = emitter.emit_mcp

        shutil.copytree(
            test_resources_dir / "setup/great_expectations",
            tmp_path / "great_expectations",
        )
        context = FileDataContext.create(tmp_path)
        context.run_checkpoint(checkpoint_name=checkpoint)

        emitter.write_to_file(tmp_path / "ge_mcps.json")

        mce_helpers.check_golden_file(
            pytestconfig,
            output_path=tmp_path / "ge_mcps.json",
            golden_path=test_resources_dir / golden_json,
            ignore_paths=[],
        )
