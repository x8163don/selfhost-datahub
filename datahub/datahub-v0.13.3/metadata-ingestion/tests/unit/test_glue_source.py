import json
from pathlib import Path
from typing import Any, Dict, Optional, Tuple, Type, cast
from unittest.mock import patch

import pydantic
import pytest
from botocore.stub import Stubber
from freezegun import freeze_time

from datahub.ingestion.api.common import PipelineContext
from datahub.ingestion.extractor.schema_util import avro_schema_to_mce_fields
from datahub.ingestion.sink.file import write_metadata_file
from datahub.ingestion.source.aws.glue import GlueSource, GlueSourceConfig
from datahub.ingestion.source.state.sql_common_state import (
    BaseSQLAlchemyCheckpointState,
)
from datahub.metadata.com.linkedin.pegasus2avro.schema import (
    ArrayTypeClass,
    MapTypeClass,
    RecordTypeClass,
    StringTypeClass,
)
from datahub.utilities.hive_schema_to_avro import get_avro_schema_for_hive_column
from tests.test_helpers import mce_helpers
from tests.test_helpers.state_helpers import (
    get_current_checkpoint_from_pipeline,
    run_and_get_pipeline,
    validate_all_providers_have_committed_successfully,
)
from tests.test_helpers.type_helpers import PytestConfig
from tests.unit.test_glue_source_stubs import (
    databases_1,
    databases_2,
    get_bucket_tagging,
    get_databases_delta_response,
    get_databases_response,
    get_databases_response_with_resource_link,
    get_dataflow_graph_response_1,
    get_dataflow_graph_response_2,
    get_delta_tables_response_1,
    get_delta_tables_response_2,
    get_jobs_response,
    get_jobs_response_empty,
    get_object_body_1,
    get_object_body_2,
    get_object_response_1,
    get_object_response_2,
    get_object_tagging,
    get_tables_response_1,
    get_tables_response_2,
    get_tables_response_for_target_database,
    resource_link_database,
    tables_1,
    tables_2,
    target_database_tables,
)

FROZEN_TIME = "2020-04-14 07:00:00"
GMS_PORT = 8080
GMS_SERVER = f"http://localhost:{GMS_PORT}"


def glue_source(
    platform_instance: Optional[str] = None,
    use_s3_bucket_tags: bool = True,
    use_s3_object_tags: bool = True,
    extract_delta_schema_from_parameters: bool = False,
) -> GlueSource:
    return GlueSource(
        ctx=PipelineContext(run_id="glue-source-test"),
        config=GlueSourceConfig(
            aws_region="us-west-2",
            extract_transforms=True,
            platform_instance=platform_instance,
            use_s3_bucket_tags=use_s3_bucket_tags,
            use_s3_object_tags=use_s3_object_tags,
            extract_delta_schema_from_parameters=extract_delta_schema_from_parameters,
        ),
    )


column_type_test_cases: Dict[str, Tuple[str, Type]] = {
    "char": ("char", StringTypeClass),
    "array": ("array<int>", ArrayTypeClass),
    "map": ("map<string, int>", MapTypeClass),
    "struct": ("struct<a:int, b:string>", RecordTypeClass),
}


@pytest.mark.parametrize(
    "hive_column_type, expected_type",
    column_type_test_cases.values(),
    ids=column_type_test_cases.keys(),
)
def test_column_type(hive_column_type: str, expected_type: Type) -> None:
    avro_schema = get_avro_schema_for_hive_column(
        f"test_column_{hive_column_type}", hive_column_type
    )
    schema_fields = avro_schema_to_mce_fields(json.dumps(avro_schema))
    actual_schema_field_type = schema_fields[0].type
    assert isinstance(actual_schema_field_type.type, expected_type)


@pytest.mark.parametrize(
    "platform_instance, mce_file, mce_golden_file",
    [
        (None, "glue_mces.json", "glue_mces_golden.json"),
        (
            "some_instance_name",
            "glue_mces_platform_instance.json",
            "glue_mces_platform_instance_golden.json",
        ),
    ],
)
@freeze_time(FROZEN_TIME)
def test_glue_ingest(
    tmp_path: Path,
    pytestconfig: PytestConfig,
    platform_instance: str,
    mce_file: str,
    mce_golden_file: str,
) -> None:
    glue_source_instance = glue_source(platform_instance=platform_instance)

    with Stubber(glue_source_instance.glue_client) as glue_stubber:
        glue_stubber.add_response("get_databases", get_databases_response, {})
        glue_stubber.add_response(
            "get_tables",
            get_tables_response_1,
            {"DatabaseName": "flights-database"},
        )
        glue_stubber.add_response(
            "get_tables",
            get_tables_response_2,
            {"DatabaseName": "test-database"},
        )
        glue_stubber.add_response("get_jobs", get_jobs_response, {})
        glue_stubber.add_response(
            "get_dataflow_graph",
            get_dataflow_graph_response_1,
            {"PythonScript": get_object_body_1},
        )
        glue_stubber.add_response(
            "get_dataflow_graph",
            get_dataflow_graph_response_2,
            {"PythonScript": get_object_body_2},
        )

        with Stubber(glue_source_instance.s3_client) as s3_stubber:
            for _ in range(
                len(get_tables_response_1["TableList"])
                + len(get_tables_response_2["TableList"])
            ):
                s3_stubber.add_response(
                    "get_bucket_tagging",
                    get_bucket_tagging(),
                )
                s3_stubber.add_response(
                    "get_object_tagging",
                    get_object_tagging(),
                )

            s3_stubber.add_response(
                "get_object",
                get_object_response_1(),
                {
                    "Bucket": "aws-glue-assets-123412341234-us-west-2",
                    "Key": "scripts/job-1.py",
                },
            )
            s3_stubber.add_response(
                "get_object",
                get_object_response_2(),
                {
                    "Bucket": "aws-glue-assets-123412341234-us-west-2",
                    "Key": "scripts/job-2.py",
                },
            )

            mce_objects = [wu.metadata for wu in glue_source_instance.get_workunits()]

            glue_stubber.assert_no_pending_responses()
            s3_stubber.assert_no_pending_responses()

            write_metadata_file(tmp_path / mce_file, mce_objects)

    # Verify the output.
    test_resources_dir = pytestconfig.rootpath / "tests/unit/glue"
    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=tmp_path / mce_file,
        golden_path=test_resources_dir / mce_golden_file,
    )


def test_platform_config():
    source = GlueSource(
        ctx=PipelineContext(run_id="glue-source-test"),
        config=GlueSourceConfig(aws_region="us-west-2", platform="athena"),
    )
    assert source.platform == "athena"


@pytest.mark.parametrize(
    "ignore_resource_links, all_databases_and_tables_result",
    [
        (True, ({}, [])),
        (False, ({"test-database": resource_link_database}, target_database_tables)),
    ],
)
def test_ignore_resource_links(ignore_resource_links, all_databases_and_tables_result):
    source = GlueSource(
        ctx=PipelineContext(run_id="glue-source-test"),
        config=GlueSourceConfig(
            aws_region="eu-west-1",
            ignore_resource_links=ignore_resource_links,
        ),
    )

    with Stubber(source.glue_client) as glue_stubber:
        glue_stubber.add_response(
            "get_databases",
            get_databases_response_with_resource_link,
            {},
        )
        glue_stubber.add_response(
            "get_tables",
            get_tables_response_for_target_database,
            {"DatabaseName": "test-database"},
        )

        assert source.get_all_databases_and_tables() == all_databases_and_tables_result


def test_platform_must_be_valid():
    with pytest.raises(pydantic.ValidationError):
        GlueSource(
            ctx=PipelineContext(run_id="glue-source-test"),
            config=GlueSourceConfig(aws_region="us-west-2", platform="data-warehouse"),
        )


def test_config_without_platform():
    source = GlueSource(
        ctx=PipelineContext(run_id="glue-source-test"),
        config=GlueSourceConfig(aws_region="us-west-2"),
    )
    assert source.platform == "glue"


@freeze_time(FROZEN_TIME)
def test_glue_stateful(pytestconfig, tmp_path, mock_time, mock_datahub_graph):
    test_resources_dir = pytestconfig.rootpath / "tests/unit/glue"

    deleted_actor_golden_mcs = "{}/glue_deleted_actor_mces_golden.json".format(
        test_resources_dir
    )

    stateful_config = {
        "stateful_ingestion": {
            "enabled": True,
            "remove_stale_metadata": True,
            "fail_safe_threshold": 100.0,
            "state_provider": {
                "type": "datahub",
                "config": {"datahub_api": {"server": GMS_SERVER}},
            },
        },
    }

    source_config_dict: Dict[str, Any] = {
        "extract_transforms": False,
        "aws_region": "eu-east-1",
        **stateful_config,
    }

    pipeline_config_dict: Dict[str, Any] = {
        "source": {
            "type": "glue",
            "config": source_config_dict,
        },
        "sink": {
            # we are not really interested in the resulting events for this test
            "type": "console"
        },
        "pipeline_name": "statefulpipeline",
    }

    with patch(
        "datahub.ingestion.source.state_provider.datahub_ingestion_checkpointing_provider.DataHubGraph",
        mock_datahub_graph,
    ) as mock_checkpoint:
        mock_checkpoint.return_value = mock_datahub_graph
        with patch(
            "datahub.ingestion.source.aws.glue.GlueSource.get_all_databases_and_tables",
        ) as mock_get_all_databases_and_tables:
            tables_on_first_call = tables_1
            tables_on_second_call = tables_2
            mock_get_all_databases_and_tables.side_effect = [
                (databases_1, tables_on_first_call),
                (databases_2, tables_on_second_call),
            ]

            pipeline_run1 = run_and_get_pipeline(pipeline_config_dict)
            checkpoint1 = get_current_checkpoint_from_pipeline(pipeline_run1)

            assert checkpoint1
            assert checkpoint1.state

            # Capture MCEs of second run to validate Status(removed=true)
            deleted_mces_path = "{}/{}".format(tmp_path, "glue_deleted_mces.json")
            pipeline_config_dict["sink"]["type"] = "file"
            pipeline_config_dict["sink"]["config"] = {"filename": deleted_mces_path}

            # Do the second run of the pipeline.
            pipeline_run2 = run_and_get_pipeline(pipeline_config_dict)
            checkpoint2 = get_current_checkpoint_from_pipeline(pipeline_run2)

            assert checkpoint2
            assert checkpoint2.state

            # Validate that all providers have committed successfully.
            validate_all_providers_have_committed_successfully(
                pipeline=pipeline_run1, expected_providers=1
            )
            validate_all_providers_have_committed_successfully(
                pipeline=pipeline_run2, expected_providers=1
            )

            # Validate against golden MCEs where Status(removed=true)
            mce_helpers.check_golden_file(
                pytestconfig,
                output_path=deleted_mces_path,
                golden_path=deleted_actor_golden_mcs,
            )

            # Perform all assertions on the states. The deleted table should not be
            # part of the second state
            state1 = cast(BaseSQLAlchemyCheckpointState, checkpoint1.state)
            state2 = cast(BaseSQLAlchemyCheckpointState, checkpoint2.state)
            difference_urns = set(
                state1.get_urns_not_in(type="*", other_checkpoint_state=state2)
            )
            assert difference_urns == {
                "urn:li:dataset:(urn:li:dataPlatform:glue,flights-database.avro,PROD)",
                "urn:li:container:0b9f1f731ecf6743be6207fec3dc9cba",
            }


def test_glue_with_delta_schema_ingest(
    tmp_path: Path,
    pytestconfig: PytestConfig,
) -> None:
    glue_source_instance = glue_source(
        platform_instance="delta_platform_instance",
        use_s3_bucket_tags=False,
        use_s3_object_tags=False,
        extract_delta_schema_from_parameters=True,
    )

    with Stubber(glue_source_instance.glue_client) as glue_stubber:
        glue_stubber.add_response("get_databases", get_databases_delta_response, {})
        glue_stubber.add_response(
            "get_tables",
            get_delta_tables_response_1,
            {"DatabaseName": "delta-database"},
        )
        glue_stubber.add_response("get_jobs", get_jobs_response_empty, {})

        mce_objects = [wu.metadata for wu in glue_source_instance.get_workunits()]

        glue_stubber.assert_no_pending_responses()

        assert glue_source_instance.get_report().num_dataset_valid_delta_schema == 1

        write_metadata_file(tmp_path / "glue_delta_mces.json", mce_objects)

    # Verify the output.
    test_resources_dir = pytestconfig.rootpath / "tests/unit/glue"
    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=tmp_path / "glue_delta_mces.json",
        golden_path=test_resources_dir / "glue_delta_mces_golden.json",
    )


def test_glue_with_malformed_delta_schema_ingest(
    tmp_path: Path,
    pytestconfig: PytestConfig,
) -> None:
    glue_source_instance = glue_source(
        platform_instance="delta_platform_instance",
        use_s3_bucket_tags=False,
        use_s3_object_tags=False,
        extract_delta_schema_from_parameters=True,
    )

    with Stubber(glue_source_instance.glue_client) as glue_stubber:
        glue_stubber.add_response("get_databases", get_databases_delta_response, {})
        glue_stubber.add_response(
            "get_tables",
            get_delta_tables_response_2,
            {"DatabaseName": "delta-database"},
        )
        glue_stubber.add_response("get_jobs", get_jobs_response_empty, {})

        mce_objects = [wu.metadata for wu in glue_source_instance.get_workunits()]

        glue_stubber.assert_no_pending_responses()

        assert glue_source_instance.get_report().num_dataset_invalid_delta_schema == 1

        write_metadata_file(tmp_path / "glue_malformed_delta_mces.json", mce_objects)

    # Verify the output.
    test_resources_dir = pytestconfig.rootpath / "tests/unit/glue"
    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=tmp_path / "glue_malformed_delta_mces.json",
        golden_path=test_resources_dir / "glue_malformed_delta_mces_golden.json",
    )
