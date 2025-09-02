from typing import Any, Dict

import pytest
from freezegun import freeze_time

from datahub.ingestion.graph.client import DatahubClientConfig
from datahub.ingestion.run.pipeline import Pipeline
from datahub.ingestion.source.metadata import business_glossary
from tests.test_helpers import mce_helpers

FROZEN_TIME = "2020-04-14 07:00:00"


def get_default_recipe(
    glossary_yml_file_path: str, event_output_file_path: str, enable_auto_id: bool
) -> Dict[str, Any]:
    return {
        "source": {
            "type": "datahub-business-glossary",
            "config": {
                "file": glossary_yml_file_path,
                "enable_auto_id": enable_auto_id,
            },
        },
        "sink": {
            "type": "file",
            "config": {
                "filename": event_output_file_path,
            },
        },
    }


@pytest.mark.parametrize(
    "enable_auto_id, golden_file",
    [
        (False, "glossary_events_golden.json"),
        (True, "glossary_events_auto_id_golden.json"),
    ],
)
@freeze_time(FROZEN_TIME)
@pytest.mark.integration
def test_glossary_ingest(
    mock_datahub_graph, pytestconfig, tmp_path, mock_time, enable_auto_id, golden_file
):
    test_resources_dir = pytestconfig.rootpath / "tests/integration/business-glossary"

    output_mces_path: str = f"{tmp_path}/glossary_events.json"
    golden_mces_path: str = f"{test_resources_dir}/{golden_file}"

    pipeline = Pipeline.create(
        get_default_recipe(
            glossary_yml_file_path=f"{test_resources_dir}/business_glossary.yml",
            event_output_file_path=output_mces_path,
            enable_auto_id=enable_auto_id,
        )
    )
    pipeline.ctx.graph = mock_datahub_graph(
        DatahubClientConfig()
    )  # Mock to resolve domain
    pipeline.run()
    pipeline.raise_from_status()

    # Verify the output.
    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=output_mces_path,
        golden_path=golden_mces_path,
    )


@freeze_time(FROZEN_TIME)
def test_auto_id_creation_on_reserved_char():
    id_: str = business_glossary.create_id(["pii", "secure % password"], None, False)
    assert id_ == "24baf9389cc05c162c7148c96314d733"
