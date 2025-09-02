import json
import logging
import pathlib
import sys
from typing import Any, Dict, cast
from unittest import mock

import pytest
from freezegun import freeze_time
from requests.adapters import ConnectionError
from tableauserverclient.models import (
    DatasourceItem,
    ProjectItem,
    ViewItem,
    WorkbookItem,
)

from datahub.configuration.source_common import DEFAULT_ENV
from datahub.emitter.mce_builder import make_schema_field_urn
from datahub.ingestion.run.pipeline import Pipeline, PipelineContext
from datahub.ingestion.source.tableau import TableauConfig, TableauSource
from datahub.ingestion.source.tableau_common import (
    TableauLineageOverrides,
    TableauUpstreamReference,
)
from datahub.metadata.com.linkedin.pegasus2avro.dataset import (
    DatasetLineageType,
    FineGrainedLineage,
    FineGrainedLineageDownstreamType,
    FineGrainedLineageUpstreamType,
    UpstreamLineage,
)
from datahub.metadata.schema_classes import MetadataChangeProposalClass, UpstreamClass
from tests.test_helpers import mce_helpers, test_connection_helpers
from tests.test_helpers.state_helpers import (
    get_current_checkpoint_from_pipeline,
    validate_all_providers_have_committed_successfully,
)

FROZEN_TIME = "2021-12-07 07:00:00"

GMS_PORT = 8080
GMS_SERVER = f"http://localhost:{GMS_PORT}"

test_resources_dir = pathlib.Path(__file__).parent

config_source_default = {
    "username": "username",
    "password": "pass`",
    "connect_uri": "https://do-not-connect",
    "site": "acryl",
    "projects": ["default", "Project 2", "Samples"],
    "extract_project_hierarchy": False,
    "page_size": 10,
    "ingest_tags": True,
    "ingest_owner": True,
    "ingest_tables_external": True,
    "default_schema_map": {
        "dvdrental": "public",
        "someotherdb": "schema",
    },
    "platform_instance_map": {"postgres": "demo_postgres_instance"},
    "extract_usage_stats": True,
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


def enable_logging():
    # set logging to console
    logging.getLogger().addHandler(logging.StreamHandler(sys.stdout))
    logging.getLogger().setLevel(logging.DEBUG)


def read_response(pytestconfig, file_name):
    response_json_path = f"{test_resources_dir}/setup/{file_name}"
    with open(response_json_path) as file:
        data = json.loads(file.read())
        return data


def side_effect_usage_stat(*arg, **kwargs):
    mock_pagination = mock.MagicMock()
    mock_pagination.total_available = None

    dashboard_stat: ViewItem = ViewItem()

    # Added as luid of Dashboard in workbooksConnection_state_all.json
    dashboard_stat._id = "fc9ea488-f810-4fa8-ac19-aa96018b5d66"
    dashboard_stat._total_views = 3

    # Added as luid of Sheet in workbooksConnection_state_all.json
    sheet_stat: ViewItem = ViewItem()
    sheet_stat._id = "f0779f9d-6765-47a9-a8f6-c740cfd27783"
    sheet_stat._total_views = 5

    return [dashboard_stat, sheet_stat], mock_pagination


def side_effect_project_data(*arg, **kwargs):
    mock_pagination = mock.MagicMock()
    mock_pagination.total_available = None

    project1: ProjectItem = ProjectItem(name="default")
    project1._id = "190a6a5c-63ed-4de1-8045-faeae5df5b01"

    project2: ProjectItem = ProjectItem(name="Project 2")
    project2._id = "c30aafe5-44f4-4f28-80d3-d181010a263c"

    project3: ProjectItem = ProjectItem(name="Samples")
    project3._id = "910733aa-2e95-4ac3-a2e8-71570751099d"

    project4: ProjectItem = ProjectItem(name="DenyProject")
    project4._id = "79d02655-88e5-45a6-9f9b-eeaf5fe54903"
    project4.parent_id = project1._id

    return [project1, project2, project3, project4], mock_pagination


def side_effect_datasource_data(*arg, **kwargs):
    mock_pagination = mock.MagicMock()
    mock_pagination.total_available = None

    datasource1: DatasourceItem = DatasourceItem(
        name="test publish datasource",
        project_id="190a6a5c-63ed-4de1-8045-faeae5df5b01",
    )
    datasource1._id = "ffd72f16-004a-4a7d-8f5b-a8fd18d4317d"

    datasource2: DatasourceItem = DatasourceItem(
        name="Superstore Datasource",
        project_id="910733aa-2e95-4ac3-a2e8-71570751099d",
    )
    datasource2._id = "db86f6cc-9c0e-400f-9fe0-0777f31c6ae2"

    datasource3: DatasourceItem = DatasourceItem(
        name="Customer Payment Query",
        project_id="190a6a5c-63ed-4de1-8045-faeae5df5b01",
    )
    datasource3._id = "1a4e81b9-1107-4b8c-a864-7009b6414858"

    return [
        datasource1,
        datasource2,
        datasource3,
    ], mock_pagination


def side_effect_workbook_data(*arg, **kwargs):
    mock_pagination = mock.MagicMock()
    mock_pagination.total_available = None

    workbook1: WorkbookItem = WorkbookItem(
        project_id="190a6a5c-63ed-4de1-8045-faeae5df5b01",
        name="Email Performance by Campaign",
    )
    workbook1._id = "65a404a8-48a2-4c2a-9eb0-14ee5e78b22b"

    workbook2: WorkbookItem = WorkbookItem(
        project_id="190a6a5c-63ed-4de1-8045-faeae5df5b01", name="Dvdrental Workbook"
    )
    workbook2._id = "b2c84ac6-1e37-4ca0-bf9b-62339be046fc"

    workbook3: WorkbookItem = WorkbookItem(
        project_id="190a6a5c-63ed-4de1-8045-faeae5df5b01", name="Executive Dashboard"
    )
    workbook3._id = "68ebd5b2-ecf6-4fdf-ba1a-95427baef506"

    workbook4: WorkbookItem = WorkbookItem(
        project_id="190a6a5c-63ed-4de1-8045-faeae5df5b01", name="Workbook published ds"
    )
    workbook4._id = "a059a443-7634-4abf-9e46-d147b99168be"

    workbook5: WorkbookItem = WorkbookItem(
        project_id="79d02655-88e5-45a6-9f9b-eeaf5fe54903", name="Deny Pattern WorkBook"
    )
    workbook5._id = "b45eabfe-dc3d-4331-9324-cc1b14b0549b"

    return [
        workbook1,
        workbook2,
        workbook3,
        workbook4,
        workbook5,
    ], mock_pagination


def side_effect_datasource_get_by_id(id, *arg, **kwargs):
    datasources, _ = side_effect_datasource_data()
    for ds in datasources:
        if ds._id == id:
            return ds


def tableau_ingest_common(
    pytestconfig,
    tmp_path,
    side_effect_query_metadata_response,
    golden_file_name,
    output_file_name,
    mock_datahub_graph,
    pipeline_config=config_source_default,
    sign_out_side_effect=lambda: None,
    pipeline_name="tableau-test-pipeline",
    datasources_side_effect=side_effect_datasource_data,
):
    with mock.patch(
        "datahub.ingestion.source.state_provider.datahub_ingestion_checkpointing_provider.DataHubGraph",
        mock_datahub_graph,
    ) as mock_checkpoint:
        mock_checkpoint.return_value = mock_datahub_graph

        with mock.patch("datahub.ingestion.source.tableau.Server") as mock_sdk:
            mock_client = mock.Mock()
            mocked_metadata = mock.Mock()
            mocked_metadata.query.side_effect = side_effect_query_metadata_response
            mock_client.metadata = mocked_metadata
            mock_client.auth = mock.Mock()
            mock_client.views = mock.Mock()
            mock_client.projects = mock.Mock()
            mock_client.projects.get.side_effect = side_effect_project_data
            mock_client.datasources = mock.Mock()
            mock_client.datasources.get.side_effect = datasources_side_effect
            mock_client.datasources.get_by_id.side_effect = (
                side_effect_datasource_get_by_id
            )
            mock_client.workbooks = mock.Mock()
            mock_client.workbooks.get.side_effect = side_effect_workbook_data
            mock_client.views.get.side_effect = side_effect_usage_stat
            mock_client.auth.sign_in.return_value = None
            mock_client.auth.sign_out.side_effect = sign_out_side_effect
            mock_sdk.return_value = mock_client
            mock_sdk._auth_token = "ABC"

            pipeline = Pipeline.create(
                {
                    "run_id": "tableau-test",
                    "pipeline_name": pipeline_name,
                    "source": {
                        "type": "tableau",
                        "config": pipeline_config,
                    },
                    "sink": {
                        "type": "file",
                        "config": {
                            "filename": f"{tmp_path}/{output_file_name}",
                        },
                    },
                }
            )
            pipeline.run()
            pipeline.raise_from_status()

            if golden_file_name:
                mce_helpers.check_golden_file(
                    pytestconfig,
                    output_path=f"{tmp_path}/{output_file_name}",
                    golden_path=test_resources_dir / golden_file_name,
                    ignore_paths=mce_helpers.IGNORE_PATH_TIMESTAMPS,
                )
            return pipeline


@freeze_time(FROZEN_TIME)
@pytest.mark.integration
def test_tableau_ingest(pytestconfig, tmp_path, mock_datahub_graph):
    enable_logging()
    output_file_name: str = "tableau_mces.json"
    golden_file_name: str = "tableau_mces_golden.json"
    tableau_ingest_common(
        pytestconfig,
        tmp_path,
        [
            read_response(pytestconfig, "workbooksConnection_all.json"),
            read_response(pytestconfig, "sheetsConnection_all.json"),
            read_response(pytestconfig, "dashboardsConnection_all.json"),
            read_response(pytestconfig, "embeddedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "publishedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "customSQLTablesConnection_all.json"),
            read_response(pytestconfig, "databaseTablesConnection_all.json"),
        ],
        golden_file_name,
        output_file_name,
        mock_datahub_graph,
        pipeline_name="test_tableau_ingest",
    )


@freeze_time(FROZEN_TIME)
@pytest.mark.integration
def test_tableau_test_connection_success():
    with mock.patch("datahub.ingestion.source.tableau.Server"):
        report = test_connection_helpers.run_test_connection(
            TableauSource, config_source_default
        )
        test_connection_helpers.assert_basic_connectivity_success(report)


@freeze_time(FROZEN_TIME)
@pytest.mark.integration
def test_tableau_test_connection_failure():
    report = test_connection_helpers.run_test_connection(
        TableauSource, config_source_default
    )
    test_connection_helpers.assert_basic_connectivity_failure(report, "Unable to login")


@freeze_time(FROZEN_TIME)
@pytest.mark.integration
def test_tableau_cll_ingest(pytestconfig, tmp_path, mock_datahub_graph):
    enable_logging()
    output_file_name: str = "tableau_mces_cll.json"
    golden_file_name: str = "tableau_cll_mces_golden.json"

    new_pipeline_config: Dict[Any, Any] = {
        **config_source_default,
        "extract_lineage_from_unsupported_custom_sql_queries": True,
        "force_extraction_of_lineage_from_custom_sql_queries": False,
        "sql_parsing_disable_schema_awareness": False,
        "extract_column_level_lineage": True,
    }

    tableau_ingest_common(
        pytestconfig=pytestconfig,
        tmp_path=tmp_path,
        side_effect_query_metadata_response=[
            read_response(pytestconfig, "workbooksConnection_all.json"),
            read_response(pytestconfig, "sheetsConnection_all.json"),
            read_response(pytestconfig, "dashboardsConnection_all.json"),
            read_response(pytestconfig, "embeddedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "publishedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "customSQLTablesConnection_all.json"),
            read_response(pytestconfig, "databaseTablesConnection_all.json"),
        ],
        golden_file_name=golden_file_name,
        output_file_name=output_file_name,
        mock_datahub_graph=mock_datahub_graph,
        pipeline_name="test_tableau_cll_ingest",
        pipeline_config=new_pipeline_config,
    )


@freeze_time(FROZEN_TIME)
@pytest.mark.integration
def test_project_pattern(pytestconfig, tmp_path, mock_datahub_graph):
    enable_logging()
    output_file_name: str = "tableau_project_pattern_mces.json"
    golden_file_name: str = "tableau_mces_golden.json"

    new_config = config_source_default.copy()
    del new_config["projects"]

    new_config["project_pattern"] = {"allow": ["^default$", "^Project 2$", "^Samples$"]}

    tableau_ingest_common(
        pytestconfig,
        tmp_path,
        [
            read_response(pytestconfig, "workbooksConnection_all.json"),
            read_response(pytestconfig, "sheetsConnection_all.json"),
            read_response(pytestconfig, "dashboardsConnection_all.json"),
            read_response(pytestconfig, "embeddedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "publishedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "customSQLTablesConnection_all.json"),
            read_response(pytestconfig, "databaseTablesConnection_all.json"),
        ],
        golden_file_name,
        output_file_name,
        mock_datahub_graph,
        pipeline_config=new_config,
        pipeline_name="test_project_pattern",
    )


@freeze_time(FROZEN_TIME)
@pytest.mark.integration
def test_project_path_pattern(pytestconfig, tmp_path, mock_datahub_graph):
    enable_logging()
    output_file_name: str = "tableau_project_path_mces.json"
    golden_file_name: str = "tableau_project_path_mces_golden.json"

    new_config = config_source_default.copy()
    del new_config["projects"]

    new_config["project_pattern"] = {"allow": ["^default/DenyProject$"]}

    tableau_ingest_common(
        pytestconfig,
        tmp_path,
        [
            read_response(pytestconfig, "workbooksConnection_all.json"),
            read_response(pytestconfig, "sheetsConnection_all.json"),
            read_response(pytestconfig, "dashboardsConnection_all.json"),
            read_response(pytestconfig, "embeddedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "publishedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "customSQLTablesConnection_all.json"),
            read_response(pytestconfig, "databaseTablesConnection_all.json"),
        ],
        golden_file_name,
        output_file_name,
        mock_datahub_graph,
        pipeline_config=new_config,
        pipeline_name="test_project_path_pattern",
    )


@freeze_time(FROZEN_TIME)
@pytest.mark.integration
def test_project_hierarchy(pytestconfig, tmp_path, mock_datahub_graph):
    enable_logging()

    output_file_name: str = "tableau_nested_project_mces.json"
    golden_file_name: str = "tableau_nested_project_mces_golden.json"

    new_config = config_source_default.copy()
    del new_config["projects"]
    new_config["project_pattern"] = {"allow": ["^default$", "^Project 2$", "^Samples$"]}
    new_config["extract_project_hierarchy"] = True

    tableau_ingest_common(
        pytestconfig,
        tmp_path,
        [
            read_response(pytestconfig, "workbooksConnection_all.json"),
            read_response(pytestconfig, "sheetsConnection_all.json"),
            read_response(pytestconfig, "dashboardsConnection_all.json"),
            read_response(pytestconfig, "embeddedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "publishedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "customSQLTablesConnection_all.json"),
            read_response(pytestconfig, "databaseTablesConnection_all.json"),
        ],
        golden_file_name,
        output_file_name,
        mock_datahub_graph,
        pipeline_config=new_config,
        pipeline_name="test_project_hierarchy",
    )


@freeze_time(FROZEN_TIME)
@pytest.mark.integration
def test_extract_all_project(pytestconfig, tmp_path, mock_datahub_graph):
    enable_logging()
    output_file_name: str = "tableau_extract_all_project_mces.json"
    golden_file_name: str = "tableau_extract_all_project_mces_golden.json"

    new_config = config_source_default.copy()
    del new_config[
        "projects"
    ]  # in absence of projects the ingestion should extract all projects

    tableau_ingest_common(
        pytestconfig,
        tmp_path,
        [
            read_response(pytestconfig, "workbooksConnection_all.json"),
            read_response(pytestconfig, "sheetsConnection_all.json"),
            read_response(pytestconfig, "dashboardsConnection_all.json"),
            read_response(pytestconfig, "embeddedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "publishedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "customSQLTablesConnection_all.json"),
            read_response(pytestconfig, "databaseTablesConnection_all.json"),
        ],
        golden_file_name,
        output_file_name,
        mock_datahub_graph,
        pipeline_config=new_config,
    )


def test_value_error_projects_and_project_pattern(
    pytestconfig, tmp_path, mock_datahub_graph
):
    # Ingestion should raise ValueError
    output_file_name: str = "tableau_project_pattern_precedence_mces.json"
    golden_file_name: str = "tableau_project_pattern_precedence_mces_golden.json"

    new_config = config_source_default.copy()
    new_config["projects"] = ["default"]
    new_config["project_pattern"] = {"allow": ["^Samples$"]}

    try:
        tableau_ingest_common(
            pytestconfig,
            tmp_path,
            [
                read_response(pytestconfig, "workbooksConnection_all.json"),
                read_response(pytestconfig, "sheetsConnection_all.json"),
                read_response(pytestconfig, "dashboardsConnection_all.json"),
                read_response(pytestconfig, "embeddedDatasourcesConnection_all.json"),
                read_response(pytestconfig, "publishedDatasourcesConnection_all.json"),
                read_response(pytestconfig, "customSQLTablesConnection_all.json"),
                read_response(pytestconfig, "databaseTablesConnection_all.json"),
            ],
            golden_file_name,
            output_file_name,
            mock_datahub_graph,
            pipeline_config=new_config,
        )
    except Exception as e:
        assert "projects is deprecated. Please use project_pattern only" in str(e)


@freeze_time(FROZEN_TIME)
@pytest.mark.integration
def test_tableau_ingest_with_platform_instance(
    pytestconfig, tmp_path, mock_datahub_graph
):
    enable_logging()
    output_file_name: str = "tableau_with_platform_instance_mces.json"
    golden_file_name: str = "tableau_with_platform_instance_mces_golden.json"

    config_source = {
        "username": "username",
        "password": "pass`",
        "connect_uri": "https://do-not-connect",
        "site": "acryl",
        "platform_instance": "acryl_site1",
        "projects": ["default", "Project 2"],
        "page_size": 10,
        "ingest_tags": True,
        "ingest_owner": True,
        "ingest_tables_external": True,
        "default_schema_map": {
            "dvdrental": "public",
            "someotherdb": "schema",
        },
        "platform_instance_map": {"postgres": "demo_postgres_instance"},
        "extract_usage_stats": True,
        "extract_project_hierarchy": False,
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

    tableau_ingest_common(
        pytestconfig,
        tmp_path,
        [
            read_response(pytestconfig, "workbooksConnection_all.json"),
            read_response(pytestconfig, "sheetsConnection_all.json"),
            read_response(pytestconfig, "dashboardsConnection_all.json"),
            read_response(pytestconfig, "embeddedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "publishedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "customSQLTablesConnection_all.json"),
            read_response(pytestconfig, "databaseTablesConnection_all.json"),
        ],
        golden_file_name,
        output_file_name,
        mock_datahub_graph,
        config_source,
        pipeline_name="test_tableau_ingest_with_platform_instance",
    )


def test_lineage_overrides():
    enable_logging()
    # Simple - specify platform instance to presto table
    assert (
        TableauUpstreamReference(
            "presto_catalog",
            "test-database-id",
            "test-schema",
            "test-table",
            "presto",
        ).make_dataset_urn(
            env=DEFAULT_ENV, platform_instance_map={"presto": "my_presto_instance"}
        )
        == "urn:li:dataset:(urn:li:dataPlatform:presto,my_presto_instance.presto_catalog.test-schema.test-table,PROD)"
    )

    # Transform presto urn to hive urn
    # resulting platform instance for hive = mapped platform instance + presto_catalog
    assert (
        TableauUpstreamReference(
            "presto_catalog",
            "test-database-id",
            "test-schema",
            "test-table",
            "presto",
        ).make_dataset_urn(
            env=DEFAULT_ENV,
            platform_instance_map={"presto": "my_instance"},
            lineage_overrides=TableauLineageOverrides(
                platform_override_map={"presto": "hive"},
            ),
        )
        == "urn:li:dataset:(urn:li:dataPlatform:hive,my_instance.presto_catalog.test-schema.test-table,PROD)"
    )

    # transform hive urn to presto urn
    assert (
        TableauUpstreamReference(
            None,
            None,
            "test-schema",
            "test-table",
            "hive",
        ).make_dataset_urn(
            env=DEFAULT_ENV,
            platform_instance_map={"hive": "my_presto_instance.presto_catalog"},
            lineage_overrides=TableauLineageOverrides(
                platform_override_map={"hive": "presto"},
            ),
        )
        == "urn:li:dataset:(urn:li:dataPlatform:presto,my_presto_instance.presto_catalog.test-schema.test-table,PROD)"
    )


def test_database_hostname_to_platform_instance_map():
    enable_logging()
    # Simple - snowflake table
    assert (
        TableauUpstreamReference(
            "test-database-name",
            "test-database-id",
            "test-schema",
            "test-table",
            "snowflake",
        ).make_dataset_urn(env=DEFAULT_ENV, platform_instance_map={})
        == "urn:li:dataset:(urn:li:dataPlatform:snowflake,test-database-name.test-schema.test-table,PROD)"
    )

    # Finding platform instance based off hostname to platform instance mappings
    assert (
        TableauUpstreamReference(
            "test-database-name",
            "test-database-id",
            "test-schema",
            "test-table",
            "snowflake",
        ).make_dataset_urn(
            env=DEFAULT_ENV,
            platform_instance_map={},
            database_hostname_to_platform_instance_map={
                "test-hostname": "test-platform-instance"
            },
            database_server_hostname_map={"test-database-id": "test-hostname"},
        )
        == "urn:li:dataset:(urn:li:dataPlatform:snowflake,test-platform-instance.test-database-name.test-schema.test-table,PROD)"
    )


@freeze_time(FROZEN_TIME)
def test_tableau_stateful(pytestconfig, tmp_path, mock_time, mock_datahub_graph):
    output_file_name: str = "tableau_mces.json"
    golden_file_name: str = "tableau_mces_golden.json"
    output_file_deleted_name: str = "tableau_mces_deleted_stateful.json"
    golden_file_deleted_name: str = "tableau_mces_golden_deleted_stateful.json"

    pipeline_run1 = tableau_ingest_common(
        pytestconfig,
        tmp_path,
        [
            read_response(pytestconfig, "workbooksConnection_all.json"),
            read_response(pytestconfig, "sheetsConnection_all.json"),
            read_response(pytestconfig, "dashboardsConnection_all.json"),
            read_response(pytestconfig, "embeddedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "publishedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "customSQLTablesConnection_all.json"),
            read_response(pytestconfig, "databaseTablesConnection_all.json"),
        ],
        golden_file_name,
        output_file_name,
        mock_datahub_graph,
    )

    checkpoint1 = get_current_checkpoint_from_pipeline(pipeline_run1)
    assert checkpoint1
    assert checkpoint1.state

    pipeline_run2 = tableau_ingest_common(
        pytestconfig,
        tmp_path,
        [read_response(pytestconfig, "workbooksConnection_all_stateful.json")],
        golden_file_deleted_name,
        output_file_deleted_name,
        mock_datahub_graph,
    )
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

    # Perform all assertions on the states. The deleted table should not be
    # part of the second state
    state1 = checkpoint1.state
    state2 = checkpoint2.state

    difference_dataset_urns = list(
        state1.get_urns_not_in(type="dataset", other_checkpoint_state=state2)
    )

    assert len(difference_dataset_urns) == 35
    deleted_dataset_urns = [
        "urn:li:dataset:(urn:li:dataPlatform:tableau,dfe2c02a-54b7-f7a2-39fc-c651da2f6ad8,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:tableau,d00f4ba6-707e-4684-20af-69eb47587cc2,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:tableau,4fb670d5-3e19-9656-e684-74aa9729cf18,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:webdata-direct:marketo-marketo,marketo.activity7,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:external,sample - superstore%2C %28new%29.xls.returns,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:tableau,22b0b4c3-6b85-713d-a161-5a87fdd78f40,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:postgres,demo_postgres_instance.dvdrental.public.actor,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:tableau,3ade7817-ae27-259e-8e48-1570e7f932f6,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:tableau,4644ccb1-2adc-cf26-c654-04ed1dcc7090,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:tableau,618c87db-5959-338b-bcc7-6f5f4cc0b6c6,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:webdata-direct:servicenowitsm-servicenowitsm,ven01911.sys_user_group,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:postgres,demo_postgres_instance.dvdrental.public.customer,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:webdata-direct:marketo-marketo,marketo.activity11,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:webdata-direct:servicenowitsm-servicenowitsm,ven01911.task,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:tableau,d8d4c0ea-3162-fa11-31e6-26675da44a38,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:tableau,801c95e3-b07e-7bfe-3789-a561c7beccd3,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:postgres,demo_postgres_instance.dvdrental.public.address,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:webdata-direct:marketo-marketo,marketo.activity6,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:webdata-direct:servicenowitsm-servicenowitsm,ven01911.incident,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:postgres,demo_postgres_instance.dvdrental.public.payment,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:webdata-direct:servicenowitsm-servicenowitsm,ven01911.cmdb_ci,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:webdata-direct:servicenowitsm-servicenowitsm,ven01911.sc_req_item,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:tableau,06c3e060-8133-4b58-9b53-a0fced25e056,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:tableau,00cce29f-b561-bb41-3557-8e19660bb5dd,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:webdata-direct:servicenowitsm-servicenowitsm,ven01911.problem,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:external,sample - superstore%2C %28new%29.xls.orders,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:tableau,6cbbeeb2-9f3a-00f6-2342-17139d6e97ae,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:webdata-direct:marketo-marketo,marketo.activity10,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:webdata-direct:servicenowitsm-servicenowitsm,ven01911.sc_request,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:postgres,demo_postgres_instance.dvdrental.public.staff,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:webdata-direct:marketo-marketo,marketo.campaignstable,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:external,sample - superstore%2C %28new%29.xls.people,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:webdata-direct:servicenowitsm-servicenowitsm,ven01911.sc_cat_item,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:tableau,10c6297d-0dbd-44f1-b1ba-458bea446513,PROD)",
        "urn:li:dataset:(urn:li:dataPlatform:tableau,5449c627-7462-4ef7-b492-bda46be068e3,PROD)",
    ]
    assert sorted(deleted_dataset_urns) == sorted(difference_dataset_urns)

    difference_chart_urns = list(
        state1.get_urns_not_in(type="chart", other_checkpoint_state=state2)
    )
    assert len(difference_chart_urns) == 24
    deleted_chart_urns = [
        "urn:li:chart:(tableau,222d1406-de0e-cd8d-0b94-9b45a0007e59)",
        "urn:li:chart:(tableau,38130558-4194-2e2a-3046-c0d887829cb4)",
        "urn:li:chart:(tableau,692a2da4-2a82-32c1-f713-63b8e4325d86)",
        "urn:li:chart:(tableau,f4317efd-c3e6-6ace-8fe6-e71b590bbbcc)",
        "urn:li:chart:(tableau,8a6a269a-d6de-fae4-5050-513255b40ffc)",
        "urn:li:chart:(tableau,c57a5574-db47-46df-677f-0b708dab14db)",
        "urn:li:chart:(tableau,e604255e-0573-3951-6db7-05bee48116c1)",
        "urn:li:chart:(tableau,20fc5eb7-81eb-aa18-8c39-af501c62d085)",
        "urn:li:chart:(tableau,2b5351c1-535d-4a4a-1339-c51ddd6abf8a)",
        "urn:li:chart:(tableau,2b73b9dd-4ec7-75ca-f2e9-fa1984ca8b72)",
        "urn:li:chart:(tableau,373c6466-bb0c-b319-8752-632456349261)",
        "urn:li:chart:(tableau,53b8dc2f-8ada-51f7-7422-fe82e9b803cc)",
        "urn:li:chart:(tableau,58af9ecf-b839-da50-65e1-2e1fa20e3362)",
        "urn:li:chart:(tableau,618b3e76-75c1-cb31-0c61-3f4890b72c31)",
        "urn:li:chart:(tableau,721c3c41-7a2b-16a8-3281-6f948a44be96)",
        "urn:li:chart:(tableau,7ef184c1-5a41-5ec8-723e-ae44c20aa335)",
        "urn:li:chart:(tableau,7fbc77ba-0ab6-3727-0db3-d8402a804da5)",
        "urn:li:chart:(tableau,8385ea9a-0749-754f-7ad9-824433de2120)",
        "urn:li:chart:(tableau,b207c2f2-b675-32e3-2663-17bb836a018b)",
        "urn:li:chart:(tableau,b679da5e-7d03-f01e-b2ea-01fb3c1926dc)",
        "urn:li:chart:(tableau,c14973c2-e1c3-563a-a9c1-8a408396d22a)",
        "urn:li:chart:(tableau,e70a540d-55ed-b9cc-5a3c-01ebe81a1274)",
        "urn:li:chart:(tableau,f76d3570-23b8-f74b-d85c-cc5484c2079c)",
        "urn:li:chart:(tableau,130496dc-29ca-8a89-e32b-d73c4d8b65ff)",
    ]
    assert sorted(deleted_chart_urns) == sorted(difference_chart_urns)

    difference_dashboard_urns = list(
        state1.get_urns_not_in(type="dashboard", other_checkpoint_state=state2)
    )
    assert len(difference_dashboard_urns) == 4
    deleted_dashboard_urns = [
        "urn:li:dashboard:(tableau,5dcaaf46-e6fb-2548-e763-272a7ab2c9b1)",
        "urn:li:dashboard:(tableau,8f7dd564-36b6-593f-3c6f-687ad06cd40b)",
        "urn:li:dashboard:(tableau,20e44c22-1ccd-301a-220c-7b6837d09a52)",
        "urn:li:dashboard:(tableau,39b7a1de-6276-cfc7-9b59-1d22f3bbb06b)",
    ]
    assert sorted(deleted_dashboard_urns) == sorted(difference_dashboard_urns)


def test_tableau_no_verify():
    enable_logging()
    # This test ensures that we can connect to a self-signed certificate
    # when ssl_verify is set to False.

    source = TableauSource.create(
        {
            "connect_uri": "https://self-signed.badssl.com/",
            "ssl_verify": False,
            "site": "bogus",
            # Credentials
            "username": "bogus",
            "password": "bogus",
        },
        PipelineContext(run_id="0"),
    )
    list(source.get_workunits())

    report = source.get_report().as_string()
    assert "SSL" not in report
    assert "Unable to login" in report


@freeze_time(FROZEN_TIME)
@pytest.mark.integration_batch_2
def test_tableau_signout_timeout(pytestconfig, tmp_path, mock_datahub_graph):
    enable_logging()
    output_file_name: str = "tableau_signout_timeout_mces.json"
    golden_file_name: str = "tableau_signout_timeout_mces_golden.json"
    tableau_ingest_common(
        pytestconfig,
        tmp_path,
        [
            read_response(pytestconfig, "workbooksConnection_all.json"),
            read_response(pytestconfig, "sheetsConnection_all.json"),
            read_response(pytestconfig, "dashboardsConnection_all.json"),
            read_response(pytestconfig, "embeddedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "publishedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "customSQLTablesConnection_all.json"),
            read_response(pytestconfig, "databaseTablesConnection_all.json"),
        ],
        golden_file_name,
        output_file_name,
        mock_datahub_graph,
        sign_out_side_effect=ConnectionError,
        pipeline_name="test_tableau_signout_timeout",
    )


def test_tableau_unsupported_csql():
    context = PipelineContext(run_id="0", pipeline_name="test_tableau")
    config_dict = config_source_default.copy()
    del config_dict["stateful_ingestion"]
    config = TableauConfig.parse_obj(config_dict)
    config.extract_lineage_from_unsupported_custom_sql_queries = True
    config.lineage_overrides = TableauLineageOverrides(
        database_override_map={"production database": "prod"}
    )

    def test_lineage_metadata(
        lineage, expected_entity_urn, expected_upstream_table, expected_cll
    ):
        mcp = cast(MetadataChangeProposalClass, next(iter(lineage)).metadata)
        assert mcp.aspect == UpstreamLineage(
            upstreams=[
                UpstreamClass(
                    dataset=expected_upstream_table,
                    type=DatasetLineageType.TRANSFORMED,
                )
            ],
            fineGrainedLineages=[
                FineGrainedLineage(
                    upstreamType=FineGrainedLineageUpstreamType.FIELD_SET,
                    upstreams=[
                        make_schema_field_urn(expected_upstream_table, upstream_column)
                    ],
                    downstreamType=FineGrainedLineageDownstreamType.FIELD,
                    downstreams=[
                        make_schema_field_urn(expected_entity_urn, downstream_column)
                    ],
                )
                for upstream_column, downstream_column in expected_cll.items()
            ],
        )
        assert mcp.entityUrn == expected_entity_urn

    csql_urn = "urn:li:dataset:(urn:li:dataPlatform:tableau,09988088-05ad-173c-a2f1-f33ba3a13d1a,PROD)"
    expected_upstream_table = "urn:li:dataset:(urn:li:dataPlatform:bigquery,my_bigquery_project.invent_dw.UserDetail,PROD)"
    expected_cll = {
        "user_id": "user_id",
        "source": "source",
        "user_source": "user_source",
    }

    source = TableauSource(config=config, ctx=context)

    lineage = source._create_lineage_from_unsupported_csql(
        csql_urn=csql_urn,
        csql={
            "query": "SELECT user_id, source, user_source FROM (SELECT *, ROW_NUMBER() OVER (partition BY user_id ORDER BY __partition_day DESC) AS rank_ FROM invent_dw.UserDetail ) source_user WHERE rank_ = 1",
            "isUnsupportedCustomSql": "true",
            "connectionType": "bigquery",
            "database": {
                "name": "my_bigquery_project",
                "connectionType": "bigquery",
            },
        },
        out_columns=[],
    )
    test_lineage_metadata(
        lineage=lineage,
        expected_entity_urn=csql_urn,
        expected_upstream_table=expected_upstream_table,
        expected_cll=expected_cll,
    )

    # With database as None
    lineage = source._create_lineage_from_unsupported_csql(
        csql_urn=csql_urn,
        csql={
            "query": "SELECT user_id, source, user_source FROM (SELECT *, ROW_NUMBER() OVER (partition BY user_id ORDER BY __partition_day DESC) AS rank_ FROM my_bigquery_project.invent_dw.UserDetail ) source_user WHERE rank_ = 1",
            "isUnsupportedCustomSql": "true",
            "connectionType": "bigquery",
            "database": None,
        },
        out_columns=[],
    )
    test_lineage_metadata(
        lineage=lineage,
        expected_entity_urn=csql_urn,
        expected_upstream_table=expected_upstream_table,
        expected_cll=expected_cll,
    )


@freeze_time(FROZEN_TIME)
@pytest.mark.integration
def test_get_all_datasources_failure(pytestconfig, tmp_path, mock_datahub_graph):
    output_file_name: str = "tableau_mces.json"
    golden_file_name: str = "tableau_mces_golden.json"
    tableau_ingest_common(
        pytestconfig,
        tmp_path,
        [
            read_response(pytestconfig, "workbooksConnection_all.json"),
            read_response(pytestconfig, "sheetsConnection_all.json"),
            read_response(pytestconfig, "dashboardsConnection_all.json"),
            read_response(pytestconfig, "embeddedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "publishedDatasourcesConnection_all.json"),
            read_response(pytestconfig, "customSQLTablesConnection_all.json"),
            read_response(pytestconfig, "databaseTablesConnection_all.json"),
        ],
        golden_file_name,
        output_file_name,
        mock_datahub_graph,
        pipeline_name="test_tableau_ingest",
        datasources_side_effect=ValueError("project_id must be defined."),
    )
