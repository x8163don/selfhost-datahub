import pathlib
from datetime import datetime, timezone

import pytest
from freezegun import freeze_time

from datahub.metadata.urns import CorpUserUrn, DatasetUrn
from datahub.sql_parsing.sql_parsing_aggregator import (
    KnownQueryLineageInfo,
    QueryLogSetting,
    SqlParsingAggregator,
)
from datahub.sql_parsing.sql_parsing_common import QueryType
from datahub.sql_parsing.sqlglot_lineage import ColumnLineageInfo, ColumnRef
from tests.test_helpers import mce_helpers
from tests.test_helpers.click_helpers import run_datahub_cmd

RESOURCE_DIR = pathlib.Path(__file__).parent / "aggregator_goldens"
FROZEN_TIME = "2024-02-06 01:23:45"


def _ts(ts: int) -> datetime:
    return datetime.fromtimestamp(ts, tz=timezone.utc)


@freeze_time(FROZEN_TIME)
def test_basic_lineage(pytestconfig: pytest.Config, tmp_path: pathlib.Path) -> None:
    aggregator = SqlParsingAggregator(
        platform="redshift",
        generate_lineage=True,
        generate_usage_statistics=False,
        generate_operations=False,
        query_log=QueryLogSetting.STORE_ALL,
    )

    aggregator.add_observed_query(
        query="create table foo as select a, b from bar",
        default_db="dev",
        default_schema="public",
    )

    mcps = list(aggregator.gen_metadata())

    mce_helpers.check_goldens_stream(
        pytestconfig,
        outputs=mcps,
        golden_path=RESOURCE_DIR / "test_basic_lineage.json",
    )

    # This test also validates the query log storage functionality.
    aggregator.close()
    query_log_db = aggregator.report.query_log_path
    query_log_json = tmp_path / "query_log.json"
    run_datahub_cmd(
        [
            "check",
            "extract-sql-agg-log",
            str(query_log_db),
            "--output",
            str(query_log_json),
        ]
    )
    mce_helpers.check_golden_file(
        pytestconfig, query_log_json, RESOURCE_DIR / "test_basic_lineage_query_log.json"
    )


@freeze_time(FROZEN_TIME)
def test_overlapping_inserts(pytestconfig: pytest.Config) -> None:
    aggregator = SqlParsingAggregator(
        platform="redshift",
        generate_lineage=True,
        generate_usage_statistics=False,
        generate_operations=False,
    )

    aggregator.add_observed_query(
        query="insert into downstream (a, b) select a, b from upstream1",
        default_db="dev",
        default_schema="public",
        query_timestamp=_ts(20),
    )
    aggregator.add_observed_query(
        query="insert into downstream (a, c) select a, c from upstream2",
        default_db="dev",
        default_schema="public",
        query_timestamp=_ts(25),
    )

    mcps = list(aggregator.gen_metadata())

    mce_helpers.check_goldens_stream(
        pytestconfig,
        outputs=mcps,
        golden_path=RESOURCE_DIR / "test_overlapping_inserts.json",
    )


@freeze_time(FROZEN_TIME)
def test_temp_table(pytestconfig: pytest.Config) -> None:
    aggregator = SqlParsingAggregator(
        platform="redshift",
        generate_lineage=True,
        generate_usage_statistics=False,
        generate_operations=False,
    )

    aggregator._schema_resolver.add_raw_schema_info(
        DatasetUrn("redshift", "dev.public.bar").urn(),
        {"a": "int", "b": "int", "c": "int"},
    )

    aggregator.add_observed_query(
        query="create table foo as select a, 2*b as b from bar",
        default_db="dev",
        default_schema="public",
        session_id="session1",
    )
    aggregator.add_observed_query(
        query="create temp table foo as select a, b+c as c from bar",
        default_db="dev",
        default_schema="public",
        session_id="session2",
    )
    aggregator.add_observed_query(
        query="create table foo_session2 as select * from foo",
        default_db="dev",
        default_schema="public",
        session_id="session2",
    )
    aggregator.add_observed_query(
        query="create table foo_session3 as select * from foo",
        default_db="dev",
        default_schema="public",
        session_id="session3",
    )

    # foo_session2 should come from bar (via temp table foo), have columns a and c, and depend on bar.{a,b,c}
    # foo_session3 should come from foo, have columns a and b, and depend on bar.b

    mcps = list(aggregator.gen_metadata())

    mce_helpers.check_goldens_stream(
        pytestconfig,
        outputs=mcps,
        golden_path=RESOURCE_DIR / "test_temp_table.json",
    )


@freeze_time(FROZEN_TIME)
def test_multistep_temp_table(pytestconfig: pytest.Config) -> None:
    aggregator = SqlParsingAggregator(
        platform="redshift",
        generate_lineage=True,
        generate_usage_statistics=False,
        generate_operations=False,
    )

    aggregator.add_observed_query(
        query="create table #temp1 as select a, 2*b as b from upstream1",
        default_db="dev",
        default_schema="public",
        session_id="session1",
    )
    aggregator.add_observed_query(
        query="create table #temp2 as select b, c from upstream2",
        default_db="dev",
        default_schema="public",
        session_id="session1",
    )
    aggregator.add_observed_query(
        query="create temp table staging_foo as select up1.a, up1.b, up2.c from #temp1 up1 left join #temp2 up2 on up1.b = up2.b where up1.b > 0",
        default_db="dev",
        default_schema="public",
        session_id="session1",
    )
    aggregator.add_observed_query(
        query="insert into table prod_foo\nselect * from staging_foo",
        default_db="dev",
        default_schema="public",
        session_id="session1",
    )

    mcps = list(aggregator.gen_metadata())

    # Extra check to make sure that the report is populated correctly.
    report = aggregator.report
    assert len(report.queries_with_temp_upstreams) == 1
    assert (
        len(
            report.queries_with_temp_upstreams[
                "composite_c89ee7c127c64a5d3a42ee875305087991891c80f42a25012910524bd2c77c45"
            ]
        )
        == 4
    )

    mce_helpers.check_goldens_stream(
        pytestconfig,
        outputs=mcps,
        golden_path=RESOURCE_DIR / "test_multistep_temp_table.json",
    )


@freeze_time(FROZEN_TIME)
def test_overlapping_inserts_from_temp_tables(pytestconfig: pytest.Config) -> None:
    aggregator = SqlParsingAggregator(
        platform="redshift",
        generate_lineage=True,
        generate_usage_statistics=False,
        generate_operations=False,
    )
    report = aggregator.report

    # The "all_returns" table is populated from "#stage_in_person_returns" and "#stage_online_returns".
    # #stage_in_person_returns is populated from "in_person_returns" and "customer".
    # #stage_online_returns is populated from "online_returns", "customer", and "online_survey".

    aggregator.add_observed_query(
        query="create table #stage_in_person_returns as select ipr.customer_id, customer.customer_email, ipr.return_date "
        "from in_person_returns ipr "
        "left join customer on in_person_returns.customer_id = customer.customer_id",
        default_db="dev",
        default_schema="public",
        session_id="1234",
    )

    aggregator.add_observed_query(
        query="create table #stage_online_returns as select online_ret.customer_id, customer.customer_email, online_ret.return_date, online_survey.return_reason "
        "from online_returns online_ret "
        "left join customer on online_ret.customer_id = customer.customer_id "
        "left join online_survey on online_ret.customer_id = online_survey.customer_id and online_ret.return_id = online_survey.event_id",
        default_db="dev",
        default_schema="public",
        session_id="2323",
    )

    aggregator.add_observed_query(
        query="insert into all_returns (customer_id, customer_email, return_date) select customer_id, customer_email, return_date from #stage_in_person_returns",
        default_db="dev",
        default_schema="public",
        session_id="1234",
    )

    aggregator.add_observed_query(
        query="insert into all_returns (customer_id, customer_email, return_date, return_reason) select customer_id, customer_email, return_date, return_reason from #stage_online_returns",
        default_db="dev",
        default_schema="public",
        session_id="2323",
    )

    # We only have one create temp table, but the same insert command from multiple sessions.
    # This should get ignored.
    assert len(report.queries_with_non_authoritative_session) == 0
    aggregator.add_observed_query(
        query="insert into all_returns (customer_id, customer_email, return_date, return_reason) select customer_id, customer_email, return_date, return_reason from #stage_online_returns",
        default_db="dev",
        default_schema="public",
        session_id="5435",
    )
    assert len(report.queries_with_non_authoritative_session) == 1

    mcps = list(aggregator.gen_metadata())
    mce_helpers.check_goldens_stream(
        pytestconfig,
        outputs=mcps,
        golden_path=RESOURCE_DIR / "test_overlapping_inserts_from_temp_tables.json",
    )


@freeze_time(FROZEN_TIME)
def test_aggregate_operations(pytestconfig: pytest.Config) -> None:
    aggregator = SqlParsingAggregator(
        platform="redshift",
        generate_lineage=False,
        generate_queries=False,
        generate_usage_statistics=False,
        generate_operations=True,
    )

    aggregator.add_observed_query(
        query="create table foo as select a, b from bar",
        default_db="dev",
        default_schema="public",
        query_timestamp=_ts(20),
        user=CorpUserUrn("user1"),
    )
    aggregator.add_observed_query(
        query="create table foo as select a, b from bar",
        default_db="dev",
        default_schema="public",
        query_timestamp=_ts(25),
        user=CorpUserUrn("user2"),
    )
    aggregator.add_observed_query(
        query="create table foo as select a, b+1 as b from bar",
        default_db="dev",
        default_schema="public",
        query_timestamp=_ts(26),
        user=CorpUserUrn("user3"),
    )

    # The first query will basically be ignored, as it's a duplicate of the second one.

    mcps = list(aggregator.gen_metadata())

    mce_helpers.check_goldens_stream(
        pytestconfig,
        outputs=mcps,
        golden_path=RESOURCE_DIR / "test_aggregate_operations.json",
    )


@freeze_time(FROZEN_TIME)
def test_view_lineage(pytestconfig: pytest.Config) -> None:
    aggregator = SqlParsingAggregator(
        platform="redshift",
        generate_lineage=True,
        generate_usage_statistics=False,
        generate_operations=False,
        query_log=QueryLogSetting.STORE_ALL,
    )

    aggregator.add_view_definition(
        view_urn=DatasetUrn("redshift", "dev.public.foo"),
        view_definition="create view foo as select a, b from bar",
        default_db="dev",
        default_schema="public",
    )

    aggregator._schema_resolver.add_raw_schema_info(
        urn=DatasetUrn("redshift", "dev.public.foo").urn(),
        schema_info={"a": "int", "b": "int"},
    )
    aggregator._schema_resolver.add_raw_schema_info(
        urn=DatasetUrn("redshift", "dev.public.bar").urn(),
        schema_info={"a": "int", "b": "int"},
    )

    # Because we have schema information, despite it being registered after the view definition,
    # the confidence score should be high.

    mcps = list(aggregator.gen_metadata())

    mce_helpers.check_goldens_stream(
        pytestconfig,
        outputs=mcps,
        golden_path=RESOURCE_DIR / "test_view_lineage.json",
    )


@freeze_time(FROZEN_TIME)
def test_known_lineage_mapping(pytestconfig: pytest.Config) -> None:
    aggregator = SqlParsingAggregator(
        platform="redshift",
        generate_lineage=True,
        generate_usage_statistics=False,
        generate_operations=False,
    )

    aggregator.add_known_lineage_mapping(
        upstream_urn=DatasetUrn("redshift", "dev.public.bar").urn(),
        downstream_urn=DatasetUrn("redshift", "dev.public.foo").urn(),
    )
    aggregator.add_known_lineage_mapping(
        upstream_urn=DatasetUrn("s3", "bucket1/key1").urn(),
        downstream_urn=DatasetUrn("redshift", "dev.public.bar").urn(),
    )
    aggregator.add_known_lineage_mapping(
        upstream_urn=DatasetUrn("redshift", "dev.public.foo").urn(),
        downstream_urn=DatasetUrn("s3", "bucket2/key2").urn(),
    )

    mcps = list(aggregator.gen_metadata())

    mce_helpers.check_goldens_stream(
        pytestconfig,
        outputs=mcps,
        golden_path=RESOURCE_DIR / "test_known_lineage_mapping.json",
    )


@freeze_time(FROZEN_TIME)
def test_column_lineage_deduplication(pytestconfig: pytest.Config) -> None:
    aggregator = SqlParsingAggregator(
        platform="redshift",
        generate_lineage=True,
        generate_usage_statistics=False,
        generate_operations=False,
    )

    aggregator.add_observed_query(
        query="/* query 1 */ insert into foo (a, b, c) select a, b, c from bar",
        default_db="dev",
        default_schema="public",
    )
    aggregator.add_observed_query(
        query="/* query 2 */ insert into foo (a, b) select a, b from bar",
        default_db="dev",
        default_schema="public",
    )

    mcps = list(aggregator.gen_metadata())

    # In this case, the lineage for a and b is attributed to query 2, and
    # the lineage for c is attributed to query 1. Note that query 1 does
    # not get any credit for a and b, as they are already covered by query 2,
    # which came later and hence has higher precedence.

    mce_helpers.check_goldens_stream(
        pytestconfig,
        outputs=mcps,
        golden_path=RESOURCE_DIR / "test_column_lineage_deduplication.json",
    )


@freeze_time(FROZEN_TIME)
def test_add_known_query_lineage(pytestconfig: pytest.Config) -> None:
    aggregator = SqlParsingAggregator(
        platform="redshift",
        generate_lineage=True,
        generate_usage_statistics=False,
        generate_operations=True,
    )

    downstream_urn = DatasetUrn("redshift", "dev.public.foo").urn()
    upstream_urn = DatasetUrn("redshift", "dev.public.bar").urn()

    known_query_lineage = KnownQueryLineageInfo(
        query_text="insert into foo (a, b, c) select a, b, c from bar",
        downstream=downstream_urn,
        upstreams=[upstream_urn],
        column_lineage=[
            ColumnLineageInfo(
                downstream=ColumnRef(table=downstream_urn, column="a"),
                upstreams=[ColumnRef(table=upstream_urn, column="a")],
            ),
            ColumnLineageInfo(
                downstream=ColumnRef(table=downstream_urn, column="b"),
                upstreams=[ColumnRef(table=upstream_urn, column="b")],
            ),
            ColumnLineageInfo(
                downstream=ColumnRef(table=downstream_urn, column="c"),
                upstreams=[ColumnRef(table=upstream_urn, column="c")],
            ),
        ],
        timestamp=_ts(20),
        query_type=QueryType.INSERT,
    )

    aggregator.add_known_query_lineage(known_query_lineage)

    mcps = list(aggregator.gen_metadata())

    mce_helpers.check_goldens_stream(
        pytestconfig,
        outputs=mcps,
        golden_path=RESOURCE_DIR / "test_add_known_query_lineage.json",
    )


@freeze_time(FROZEN_TIME)
def test_table_rename(pytestconfig: pytest.Config) -> None:
    aggregator = SqlParsingAggregator(
        platform="redshift",
        generate_lineage=True,
        generate_usage_statistics=False,
        generate_operations=False,
    )

    # Register that foo_staging is renamed to foo.
    aggregator.add_table_rename(
        original_urn=DatasetUrn("redshift", "dev.public.foo_staging").urn(),
        new_urn=DatasetUrn("redshift", "dev.public.foo").urn(),
    )

    # Add an unrelated query.
    aggregator.add_observed_query(
        query="create table bar as select a, b from baz",
        default_db="dev",
        default_schema="public",
    )

    # Add the query that created the staging table.
    aggregator.add_observed_query(
        query="create table foo_staging as select a, b from foo_dep",
        default_db="dev",
        default_schema="public",
    )

    mcps = list(aggregator.gen_metadata())

    mce_helpers.check_goldens_stream(
        pytestconfig,
        outputs=mcps,
        golden_path=RESOURCE_DIR / "test_table_rename.json",
    )
