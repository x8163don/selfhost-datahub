import pytest

import datahub.ingestion.source.tableau_constant as c
from datahub.ingestion.source.tableau import TableauSource
from datahub.ingestion.source.tableau_common import get_filter_pages, make_filter


def test_tableau_source_unescapes_lt():
    res = TableauSource._clean_tableau_query_parameters(
        "select * from t where c1 << 135"
    )

    assert res == "select * from t where c1 < 135"


def test_tableau_source_unescapes_gt():
    res = TableauSource._clean_tableau_query_parameters(
        "select * from t where c1 >> 135"
    )

    assert res == "select * from t where c1 > 135"


def test_tableau_source_unescapes_gte():
    res = TableauSource._clean_tableau_query_parameters(
        "select * from t where c1 >>= 135"
    )

    assert res == "select * from t where c1 >= 135"


def test_tableau_source_unescapeslgte():
    res = TableauSource._clean_tableau_query_parameters(
        "select * from t where c1 <<= 135"
    )

    assert res == "select * from t where c1 <= 135"


def test_tableau_source_doesnt_touch_not_escaped():
    res = TableauSource._clean_tableau_query_parameters(
        "select * from t where c1 < 135 and c2 > 15"
    )

    assert res == "select * from t where c1 < 135 and c2 > 15"


TABLEAU_PARAMS = [
    "<Parameters.MyParam>",
    "<Parameters.MyParam_1>",
    "<Parameters.My Param _ 1>",
    "<Parameters.My Param 1 !@\"',.#$%^:;&*()-_+={}|\\ /<>",
    "<[Parameters].MyParam>",
    "<[Parameters].MyParam_1>",
    "<[Parameters].My Param _ 1>",
    "<[Parameters].My Param 1 !@\"',.#$%^:;&*()-_+={}|\\ /<>",
    "<Parameters.[MyParam]>",
    "<Parameters.[MyParam_1]>",
    "<Parameters.[My Param _ 1]>",
    "<Parameters.[My Param 1 !@\"',.#$%^:;&*()-_+={}|\\ /<]>",
    "<[Parameters].[MyParam]>",
    "<[Parameters].[MyParam_1]>",
    "<[Parameters].[My Param _ 1]>",
    "<[Parameters].[My Param 1 !@\"',.#$%^:;&*()-_+={}|\\ /<]>",
    "<Parameters.[My Param 1 !@\"',.#$%^:;&*()-_+={}|\\ /<>]>",
    "<[Parameters].[My Param 1 !@\"',.#$%^:;&*()-_+={}|\\ /<>]>",
]


@pytest.mark.parametrize("p", TABLEAU_PARAMS)
def test_tableau_source_cleanups_tableau_parameters_in_equi_predicates(p):
    assert (
        TableauSource._clean_tableau_query_parameters(
            f"select * from t where c1 = {p} and c2 = {p} and c3 = 7"
        )
        == "select * from t where c1 = 1 and c2 = 1 and c3 = 7"
    )


@pytest.mark.parametrize("p", TABLEAU_PARAMS)
def test_tableau_source_cleanups_tableau_parameters_in_lt_gt_predicates(p):
    assert (
        TableauSource._clean_tableau_query_parameters(
            f"select * from t where c1 << {p} and c2<<{p} and c3 >> {p} and c4>>{p} or {p} >> c1 and {p}>>c2 and {p} << c3 and {p}<<c4"
        )
        == "select * from t where c1 < 1 and c2<1 and c3 > 1 and c4>1 or 1 > c1 and 1>c2 and 1 < c3 and 1<c4"
    )


@pytest.mark.parametrize("p", TABLEAU_PARAMS)
def test_tableau_source_cleanups_tableau_parameters_in_lte_gte_predicates(p):
    assert (
        TableauSource._clean_tableau_query_parameters(
            f"select * from t where c1 <<= {p} and c2<<={p} and c3 >>= {p} and c4>>={p} or {p} >>= c1 and {p}>>=c2 and {p} <<= c3 and {p}<<=c4"
        )
        == "select * from t where c1 <= 1 and c2<=1 and c3 >= 1 and c4>=1 or 1 >= c1 and 1>=c2 and 1 <= c3 and 1<=c4"
    )


@pytest.mark.parametrize("p", TABLEAU_PARAMS)
def test_tableau_source_cleanups_tableau_parameters_in_join_predicate(p):
    assert (
        TableauSource._clean_tableau_query_parameters(
            f"select * from t1 inner join t2 on t1.id = t2.id and t2.c21 = {p} and t1.c11 = 123 + {p}"
        )
        == "select * from t1 inner join t2 on t1.id = t2.id and t2.c21 = 1 and t1.c11 = 123 + 1"
    )


@pytest.mark.parametrize("p", TABLEAU_PARAMS)
def test_tableau_source_cleanups_tableau_parameters_in_complex_expressions(p):
    assert (
        TableauSource._clean_tableau_query_parameters(
            f"select myudf1(c1, {p}, c2) / myudf2({p}) > ({p} + 3 * {p} * c5) * {p} - c4"
        )
        == "select myudf1(c1, 1, c2) / myudf2(1) > (1 + 3 * 1 * c5) * 1 - c4"
    )


@pytest.mark.parametrize("p", TABLEAU_PARAMS)
def test_tableau_source_cleanups_tableau_parameters_in_udfs(p):
    assert (
        TableauSource._clean_tableau_query_parameters(f"select myudf({p}) from t")
        == "select myudf(1) from t"
    )


def test_make_id_filter():
    ids = [i for i in range(1, 6)]
    filter_dict = {c.ID_WITH_IN: ids}
    assert make_filter(filter_dict) == f"{c.ID_WITH_IN}: [1, 2, 3, 4, 5]"


def test_make_project_filter():
    projects = ["x", "y", "z"]
    filter_dict = {c.PROJECT_NAME_WITH_IN: projects}
    assert make_filter(filter_dict) == f'{c.PROJECT_NAME_WITH_IN}: ["x", "y", "z"]'


def test_make_multiple_filters():
    ids = [i for i in range(1, 6)]
    projects = ["x", "y", "z"]
    filter_dict = {c.ID_WITH_IN: ids, c.PROJECT_NAME_WITH_IN: projects}
    assert (
        make_filter(filter_dict)
        == f'{c.ID_WITH_IN}: [1, 2, 3, 4, 5], {c.PROJECT_NAME_WITH_IN}: ["x", "y", "z"]'
    )


def test_get_filter_pages_simple():
    ids = [i for i in range(5)]
    filter_dict = {c.ID_WITH_IN: ids}
    assert get_filter_pages(filter_dict, 10) == [filter_dict]


def test_get_filter_pages_non_id_large_filter_passthrough():
    projects = [f"project{i}" for i in range(20000)]
    filter_dict = {c.PROJECT_NAME_WITH_IN: projects}
    assert get_filter_pages(filter_dict, 10) == [filter_dict]


def test_get_filter_pages_id_filter_splits_into_multiple_filters():
    page_size = 10
    num_ids = 20000
    ids = [f"id_{i}" for i in range(num_ids)]
    filter_dict = {c.ID_WITH_IN: ids}
    assert get_filter_pages(filter_dict, page_size) == [
        {c.ID_WITH_IN: filter_dict[c.ID_WITH_IN][i : i + page_size]}
        for i in range(0, num_ids, page_size)
    ]
